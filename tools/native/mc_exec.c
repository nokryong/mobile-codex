/*
 * Compatibility layer for scripts stored below Android's noexec app-data
 * directory.  It recognizes only interpreter shebangs used by the bundled
 * Node/Python runtimes and routes them to the app's native dispatcher.
 *
 * This is original code, not a copy of Termux-exec.  It intentionally does
 * not try to make arbitrary ELF files executable and never changes an unknown
 * shebang.  The hooks cover common libc and libuv launch paths.
 */
#define _GNU_SOURCE
#include "mc_paths.h"
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <spawn.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

extern char **environ;

typedef int (*execve_fn)(const char *, char *const[], char *const[]);
typedef int (*spawn_fn)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                        const posix_spawnattr_t *, char *const[], char *const[]);

static __thread int in_hook;

static execve_fn real_execve(void) {
    static execve_fn function;
    if (function == NULL) function = (execve_fn)dlsym(RTLD_NEXT, "execve");
    return function;
}

static const char *env_value(char *const envp[], const char *name) {
    size_t length = strlen(name);
    char *const *cursor = envp == NULL ? environ : envp;
    for (; cursor != NULL && *cursor != NULL; cursor++)
        if (!strncmp(*cursor, name, length) && (*cursor)[length] == '=') return *cursor + length + 1;
    return NULL;
}

static char *make_path(const char *left, const char *right) {
    size_t length = strlen(left) + strlen(right) + 2;
    char *path = malloc(length);
    if (path != NULL) snprintf(path, length, "%s/%s", left, right);
    return path;
}

static const char *file_base(const char *value) {
    const char *slash = strrchr(value, '/');
    return slash == NULL ? value : slash + 1;
}

static bool has_name(const char *value, const char *name) {
    return !strcmp(file_base(value), name);
}

static bool is_termux_shell(const char *value, const char *prefix) {
    if (!strcmp(value, "/bin/sh") || !strcmp(value, "/usr/bin/sh")) return true;
    if (prefix != NULL) {
        char *expected = make_path(prefix, "bin/sh");
        bool matches = expected != NULL && !strcmp(value, expected);
        free(expected);
        if (matches) return true;
    }
    size_t length = strlen(value), suffix = strlen("/files/usr/bin/sh");
    return length >= suffix && !strcmp(value + length - suffix, "/files/usr/bin/sh");
}

static bool is_node(const char *value) { return has_name(value, "node") || has_name(value, "nodejs"); }
static bool is_python(const char *value) {
    const char *base = file_base(value);
    return !strcmp(base, "python") || !strncmp(base, "python3", 7);
}

/* The packaged interpreter ELF names are implementation details, not `python`. */
static bool is_override(const char *value, char *const envp[], const char *name) {
    const char *override = env_value(envp, name);
    return override != NULL && override[0] != '\0' && !strcmp(value, override);
}

static bool is_node_runtime(const char *value, char *const envp[]) {
    return is_node(value) || is_override(value, envp, "MC_NODE");
}

static bool is_python_runtime(const char *value, char *const envp[]) {
    return is_python(value) || is_override(value, envp, "MC_PYTHON");
}

static char *runtime_path(const char *kind, const char *prefix, char *const envp[]) {
    const char *override = env_value(envp, !strcmp(kind, "node") ? "MC_NODE" : "MC_PYTHON");
    if (override != NULL && override[0] != '\0') return strdup(override);
    return make_path(prefix, !strcmp(kind, "node") ? "bin/node" : "bin/python3");
}

static int split_words(char *text, char *words[], int capacity) {
    char *save = NULL, *word;
    int count = 0;
    for (word = strtok_r(text, " \t\r\n", &save); word != NULL; word = strtok_r(NULL, " \t\r\n", &save)) {
        if (count == capacity) return -1;
        words[count++] = word;
    }
    return count;
}

/*
 * Parse only the safe, common subset of env's command syntax.  In particular,
 * a requested changed environment (`-i`, `NAME=value`, and similar) is not
 * redirected: dropping it would silently change the script's behavior.
 */
static bool env_words(char *words[], int count, char *const envp[], const char **command, char ***command_args, int *args_count) {
    int index = 0;
    if (count > 0 && (!strcmp(words[0], "-S") || !strcmp(words[0], "--split-string"))) index++;
    if (index >= count || words[index][0] == '-' || strchr(words[index], '=') != NULL) return false;
    *command = words[index++];
    if (!is_node_runtime(*command, envp) && !is_python_runtime(*command, envp)) return false;
    *command_args = words + index;
    *args_count = count - index;
    return true;
}

static bool usable_path(const char *path) {
    char header[2];
    struct stat st;
    int fd;
    if (stat(path, &st) != 0 || !S_ISREG(st.st_mode)) return false;
    if (access(path, X_OK) == 0) return true;
    fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    int bytes = (int)read(fd, header, sizeof(header));
    close(fd);
    return bytes == 2 && header[0] == '#' && header[1] == '!';
}

static char *find_in_path(const char *file, char *const envp[]) {
    const char *path = env_value(envp, "PATH");
    const char *segment;
    if (strchr(file, '/') != NULL) return strdup(file);
    if (path == NULL) path = "/system/bin";
    for (segment = path; ; ) {
        const char *end = strchr(segment, ':');
        size_t length = end == NULL ? strlen(segment) : (size_t)(end - segment);
        char *directory = strndup(length ? segment : ".", length ? length : 1);
        char *candidate = directory == NULL ? NULL : make_path(directory, file);
        free(directory);
        if (candidate != NULL && usable_path(candidate)) return candidate;
        free(candidate);
        if (end == NULL) break;
        segment = end + 1;
    }
    return NULL;
}

struct replacement {
    char *path;
    char **argv;
    char **owned_args;
    int owned_count;
};

static void release_replacement(struct replacement *replacement) {
    int index;
    if (replacement == NULL) return;
    free(replacement->path);
    free(replacement->argv);
    for (index = 0; index < replacement->owned_count; index++) free(replacement->owned_args[index]);
    free(replacement->owned_args);
    replacement->path = NULL;
    replacement->argv = NULL;
    replacement->owned_args = NULL;
    replacement->owned_count = 0;
}

static bool build_replacement(char *resolved, char *const interpreter_args[], int interpreter_count,
                              const char *script, char *const argv[], int argv_start,
                              struct replacement *out) {
    int count = 0, i, cursor = 0;
    while (argv[count] != NULL) count++;
    out->argv = calloc((size_t)(interpreter_count + count + 2), sizeof(*out->argv));
    if (out->argv == NULL) { free(resolved); return false; }
    if (interpreter_count > 0) {
        out->owned_args = calloc((size_t)interpreter_count, sizeof(*out->owned_args));
        if (out->owned_args == NULL) { release_replacement(out); return false; }
        out->owned_count = interpreter_count;
    }
    out->path = resolved;
    out->argv[cursor++] = resolved;
    for (i = 0; i < interpreter_count; i++) {
        out->owned_args[i] = strdup(interpreter_args[i]);
        if (out->owned_args[i] == NULL) { release_replacement(out); return false; }
        out->argv[cursor++] = out->owned_args[i];
    }
    if (script != NULL) out->argv[cursor++] = (char *)script;
    for (i = argv_start; i < count; i++) out->argv[cursor++] = argv[i];
    return true;
}

#ifdef MC_EXEC_TEST_CLOBBER
/* Host regression test: values from a returned shebang parser must be owned. */
__attribute__((noinline)) static void clobber_return_stack(void) {
    volatile char clobber[16384];
    for (size_t index = 0; index < sizeof(clobber); index++) clobber[index] = (char)index;
}
#else
static void clobber_return_stack(void) {}
#endif

/* Route `env node script.js` too: Android does not provide /usr/bin/env. */
static bool make_env_replacement(const char *target, char *const argv[], char *const envp[], struct replacement *out) {
    const char *prefix = env_value(envp, "MC_PREFIX");
    const char *command;
    char *resolved = NULL;
    int index = 1, count = 0;

    memset(out, 0, sizeof(*out));
    if (target == NULL || argv == NULL || prefix == NULL || !has_name(target, "env")) return false;
    while (argv[count] != NULL) count++;
    /* Direct env -S has shell-like quoting in a single argument; preserve it by declining. */
    if (index < count && (!strcmp(argv[index], "-S") || !strcmp(argv[index], "--split-string"))) return false;
    if (index >= count || argv[index][0] == '-' || strchr(argv[index], '=') != NULL) return false;
    command = argv[index++];
    if (!is_node_runtime(command, envp) && !is_python_runtime(command, envp)) return false;
    resolved = runtime_path(is_node_runtime(command, envp) ? "node" : "python", prefix, envp);
    if (resolved == NULL || !usable_path(resolved)) { free(resolved); return false; }
    return build_replacement(resolved, NULL, 0, NULL, argv, index, out);
}

static bool make_shell_replacement(const char *target, char *const argv[], char *const envp[], struct replacement *out) {
    const char *prefix = env_value(envp, "MC_PREFIX");
    char *resolved;
    memset(out, 0, sizeof(*out));
    if (target == NULL || argv == NULL || !is_termux_shell(target, prefix)) return false;
    resolved = strdup("/system/bin/sh");
    if (resolved == NULL) return false;
    return build_replacement(resolved, NULL, 0, NULL, argv, 1, out);
}

static bool make_replacement(const char *target, char *const argv[], char *const envp[], struct replacement *out) {
    char shebang[4097], *cursor, *resolved = NULL, *words[64];
    const char *interpreter;
    const char *prefix = env_value(envp, "MC_PREFIX");
    const char *native_dir = env_value(envp, "MC_NATIVE_DIR");
    const char *kind = NULL;
    char **interpreter_args = NULL;
    int fd, word_count, interpreter_count = 0, bytes;

    memset(out, 0, sizeof(*out));
    if (prefix == NULL || native_dir == NULL || argv == NULL || target == NULL) return false;
    fd = open(target, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    bytes = (int)read(fd, shebang, sizeof(shebang) - 1);
    close(fd);
    if (bytes < 3 || shebang[0] != '#' || shebang[1] != '!') return false;
    shebang[bytes] = '\0';
    /* Interpreter arguments end at the first line, never at the end of the read buffer. */
    char *line_end = strpbrk(shebang, "\r\n");
    if (line_end != NULL) *line_end = '\0';
    else if (bytes == (int)sizeof(shebang) - 1) return false;
    cursor = shebang + 2;
    while (*cursor == ' ' || *cursor == '\t') cursor++;
    word_count = split_words(cursor, words, (int)(sizeof(words) / sizeof(words[0])));
    if (word_count <= 0) return false;
    interpreter = words[0];
    if (has_name(interpreter, "env")) {
        if (!env_words(words + 1, word_count - 1, envp, &interpreter, &interpreter_args, &interpreter_count)) return false;
    } else {
        interpreter_args = words + 1;
        interpreter_count = word_count - 1;
    }
    if (is_termux_shell(interpreter, prefix)) kind = "sh";
    else if (is_node_runtime(interpreter, envp)) kind = "node";
    else if (is_python_runtime(interpreter, envp)) kind = "python";
    else return false;

    if (!strcmp(kind, "sh")) resolved = strdup("/system/bin/sh");
    else resolved = runtime_path(kind, prefix, envp);
    if (resolved == NULL || !usable_path(resolved)) { free(resolved); return false; }
    return build_replacement(resolved, interpreter_args, interpreter_count, target, argv, 1, out);
}

int execve(const char *path, char *const argv[], char *const envp[]) {
    execve_fn original = real_execve();
    struct replacement replacement;
    char *mapped;
    if (original == NULL) { errno = ENOSYS; return -1; }
    if (!in_hook) {
        in_hook = 1;
        if (make_env_replacement(path, argv, envp, &replacement) || make_shell_replacement(path, argv, envp, &replacement) || make_replacement(path, argv, envp, &replacement)) {
            clobber_return_stack();
            int status = original(replacement.path, replacement.argv, envp);
            release_replacement(&replacement);
            in_hook = 0;
            return status;
        }
        in_hook = 0;
    }
    mapped = mc_translate_path(path);
    int status = original(mapped == NULL ? path : mapped, argv, envp);
    int saved = errno; free(mapped); errno = saved; return status;
}

int execv(const char *path, char *const argv[]) { return execve(path, argv, environ); }

int execvp(const char *file, char *const argv[]) {
    char *path = find_in_path(file, environ);
    int status;
    if (path == NULL) { errno = ENOENT; return -1; }
    status = execve(path, argv, environ);
    free(path);
    return status;
}

int execvpe(const char *file, char *const argv[], char *const envp[]) {
    char *path = find_in_path(file, envp);
    int status;
    if (path == NULL) { errno = ENOENT; return -1; }
    status = execve(path, argv, envp);
    free(path);
    return status;
}

static int spawn_common(spawn_fn original, pid_t *pid, const char *path,
                        const posix_spawn_file_actions_t *actions, const posix_spawnattr_t *attributes,
                        char *const argv[], char *const envp[]) {
    struct replacement replacement;
    char *mapped;
    int status;
    if (original == NULL) return ENOSYS;
    if (!in_hook) {
        in_hook = 1;
        if (make_env_replacement(path, argv, envp, &replacement) || make_shell_replacement(path, argv, envp, &replacement) || make_replacement(path, argv, envp, &replacement)) {
            clobber_return_stack();
            status = original(pid, replacement.path, actions, attributes, replacement.argv, envp);
            release_replacement(&replacement);
            in_hook = 0;
            return status;
        }
        in_hook = 0;
    }
    mapped = mc_translate_path(path);
    status = original(pid, mapped == NULL ? path : mapped, actions, attributes, argv, envp);
    free(mapped);
    return status;
}

int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *actions,
                const posix_spawnattr_t *attributes, char *const argv[], char *const envp[]) {
    static spawn_fn original;
    if (original == NULL) original = (spawn_fn)dlsym(RTLD_NEXT, "posix_spawn");
    return spawn_common(original, pid, path, actions, attributes, argv, envp);
}

int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *actions,
                 const posix_spawnattr_t *attributes, char *const argv[], char *const envp[]) {
    static spawn_fn original;
    char *path = find_in_path(file, envp);
    int status;
    if (original == NULL) original = (spawn_fn)dlsym(RTLD_NEXT, "posix_spawnp");
    if (path == NULL) return ENOENT;
    status = spawn_common(original, pid, path, actions, attributes, argv, envp);
    free(path);
    return status;
}

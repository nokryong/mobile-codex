/*
 * Small Android command dispatcher for Mobile Codex's packaged runtimes.
 *
 * Android app-private files are normally mounted noexec.  This executable is
 * installed in nativeLibraryDir (where APK-native executables are allowed) and
 * invoked through symlinks in $MC_PREFIX/bin.  It deliberately supports only
 * the fixed commands bundled by the app; it is not a general executable proxy.
 */
#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static const char *base_name(const char *path) {
    if (path == NULL) return "";
    const char *slash = strrchr(path, '/');
    return slash == NULL ? path : slash + 1;
}

static int starts_with(const char *value, const char *prefix) {
    return strncmp(value, prefix, strlen(prefix)) == 0;
}

static char *join_path(const char *left, const char *right) {
    size_t need = strlen(left) + 1 + strlen(right) + 1;
    char *result = malloc(need);
    if (result != NULL) snprintf(result, need, "%s/%s", left, right);
    return result;
}

static void exec_program(const char *program, char *const args[]) {
    execv(program, args);
    fprintf(stderr, "mobile-codex: cannot start %s: %s\n", program, strerror(errno));
}

int main(int argc, char **argv) {
    const char *command = base_name(argc > 0 ? argv[0] : "");
    const char *prefix = getenv("MC_PREFIX");
    const char *native_dir = getenv("MC_NATIVE_DIR");
    const char *node_override = getenv("MC_NODE");
    const char *python_override = getenv("MC_PYTHON");
    char *node_owned = NULL, *python_owned = NULL, *script_owned = NULL;
    const char *node, *python;
    char **next;
    int result = 127;

    if (prefix == NULL || prefix[0] == '\0' || native_dir == NULL || native_dir[0] == '\0') {
        fputs("mobile-codex: MC_PREFIX and MC_NATIVE_DIR are required\n", stderr);
        return result;
    }
    node_owned = join_path(native_dir, "libnode.so");
    python_owned = join_path(native_dir, "libpython3.so");
    if (node_owned == NULL || python_owned == NULL) goto done;
    node = node_override != NULL && node_override[0] ? node_override : node_owned;
    python = python_override != NULL && python_override[0] ? python_override : python_owned;

    if (!strcmp(command, "node") || !strcmp(command, "nodejs")) {
        next = calloc((size_t)argc + 1, sizeof(*next));
        if (next == NULL) goto done;
        next[0] = (char *)node;
        for (int i = 1; i < argc; i++) next[i] = argv[i];
        exec_program(node, next);
        free(next);
        goto done;
    }
    if (!strcmp(command, "python") || starts_with(command, "python3")) {
        next = calloc((size_t)argc + 1, sizeof(*next));
        if (next == NULL) goto done;
        next[0] = (char *)python;
        for (int i = 1; i < argc; i++) next[i] = argv[i];
        exec_program(python, next);
        free(next);
        goto done;
    }
    if (!strcmp(command, "npm") || !strcmp(command, "npx")) {
        script_owned = join_path(prefix, !strcmp(command, "npm")
            ? "lib/node_modules/npm/bin/npm-cli.js"
            : "lib/node_modules/npm/bin/npx-cli.js");
        if (script_owned == NULL) goto done;
        next = calloc((size_t)argc + 2, sizeof(*next));
        if (next == NULL) goto done;
        next[0] = (char *)node;
        next[1] = script_owned;
        for (int i = 1; i < argc; i++) next[i + 1] = argv[i];
        exec_program(node, next);
        free(next);
        goto done;
    }
    if (!strcmp(command, "pip") || starts_with(command, "pip3")) {
        next = calloc((size_t)argc + 3, sizeof(*next));
        if (next == NULL) goto done;
        next[0] = (char *)python;
        next[1] = "-m";
        next[2] = "pip";
        for (int i = 1; i < argc; i++) next[i + 2] = argv[i];
        exec_program(python, next);
        free(next);
        goto done;
    }
    fprintf(stderr, "mobile-codex: unsupported runtime alias '%s'\n", command);
done:
    free(script_owned);
    free(python_owned);
    free(node_owned);
    return result;
}

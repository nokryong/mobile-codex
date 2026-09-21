/*
 * Exact compatibility translation for binaries built against the Termux
 * prefix. This is intentionally a path adapter, not a general mount layer.
 */
#define _GNU_SOURCE
#include "mc_paths.h"

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <dirent.h>
#include <unistd.h>

static const char TERMUX_PREFIX[] = "/data/data/com.termux/files/usr";
static const char TERMUX_TMP[] = "/data/data/com.termux/files/usr/tmp";

static bool at_boundary(const char *path, const char *prefix) {
    size_t length = strlen(prefix);
    return !strncmp(path, prefix, length) && (path[length] == '\0' || path[length] == '/');
}

static char *under_root(const char *root, const char *suffix) {
    size_t root_length, suffix_length, trim;
    char *result;
    if (root == NULL || root[0] == '\0') return NULL;
    root_length = strlen(root);
    suffix_length = strlen(suffix);
    trim = root_length > 1 && root[root_length - 1] == '/' ? 1 : 0;
    result = malloc(root_length - trim + suffix_length + 1);
    if (result == NULL) return NULL;
    memcpy(result, root, root_length - trim);
    memcpy(result + root_length - trim, suffix, suffix_length + 1);
    return result;
}

char *mc_translate_path(const char *path) {
    const char *prefix, *tmp;
    if (path == NULL || !at_boundary(path, TERMUX_PREFIX)) return NULL;
    tmp = getenv("TMPDIR");
    if (at_boundary(path, TERMUX_TMP) && tmp != NULL && tmp[0] != '\0')
        return under_root(tmp, path + strlen(TERMUX_TMP));
    prefix = getenv("MC_PREFIX");
    return under_root(prefix, path + strlen(TERMUX_PREFIX));
}

static void *next_symbol(const char *name) { return dlsym(RTLD_NEXT, name); }
static bool needs_mode(int flags) { return (flags & O_CREAT) || ((flags & O_TMPFILE) == O_TMPFILE); }

int open(const char *path, int flags, ...) {
    typedef int (*function_type)(const char *, int, ...);
    static function_type original;
    char *mapped = mc_translate_path(path);
    mode_t mode = 0;
    int result, saved;
    if (needs_mode(flags)) { va_list values; va_start(values, flags); mode = va_arg(values, mode_t); va_end(values); }
    if (original == NULL) original = (function_type)next_symbol("open");
    if (original == NULL) { free(mapped); errno = ENOSYS; return -1; }
    result = needs_mode(flags) ? original(mapped == NULL ? path : mapped, flags, mode) : original(mapped == NULL ? path : mapped, flags);
    saved = errno; free(mapped); errno = saved; return result;
}

int open64(const char *path, int flags, ...) {
    typedef int (*function_type)(const char *, int, ...);
    static function_type original;
    char *mapped = mc_translate_path(path);
    mode_t mode = 0;
    int result, saved;
    if (needs_mode(flags)) { va_list values; va_start(values, flags); mode = va_arg(values, mode_t); va_end(values); }
    if (original == NULL) original = (function_type)next_symbol("open64");
    if (original == NULL) { free(mapped); errno = ENOSYS; return -1; }
    result = needs_mode(flags) ? original(mapped == NULL ? path : mapped, flags, mode) : original(mapped == NULL ? path : mapped, flags);
    saved = errno; free(mapped); errno = saved; return result;
}

int openat(int directory, const char *path, int flags, ...) {
    typedef int (*function_type)(int, const char *, int, ...);
    static function_type original;
    char *mapped = mc_translate_path(path);
    mode_t mode = 0;
    int result, saved;
    if (needs_mode(flags)) { va_list values; va_start(values, flags); mode = va_arg(values, mode_t); va_end(values); }
    if (original == NULL) original = (function_type)next_symbol("openat");
    if (original == NULL) { free(mapped); errno = ENOSYS; return -1; }
    result = needs_mode(flags) ? original(directory, mapped == NULL ? path : mapped, flags, mode) : original(directory, mapped == NULL ? path : mapped, flags);
    saved = errno; free(mapped); errno = saved; return result;
}

int openat64(int directory, const char *path, int flags, ...) {
    typedef int (*function_type)(int, const char *, int, ...);
    static function_type original;
    char *mapped = mc_translate_path(path);
    mode_t mode = 0;
    int result, saved;
    if (needs_mode(flags)) { va_list values; va_start(values, flags); mode = va_arg(values, mode_t); va_end(values); }
    if (original == NULL) original = (function_type)next_symbol("openat64");
    if (original == NULL) { free(mapped); errno = ENOSYS; return -1; }
    result = needs_mode(flags) ? original(directory, mapped == NULL ? path : mapped, flags, mode) : original(directory, mapped == NULL ? path : mapped, flags);
    saved = errno; free(mapped); errno = saved; return result;
}

FILE *fopen(const char *path, const char *mode) {
    typedef FILE *(*function_type)(const char *, const char *);
    static function_type original;
    char *mapped = mc_translate_path(path);
    FILE *result; int saved;
    if (original == NULL) original = (function_type)next_symbol("fopen");
    if (original == NULL) { free(mapped); errno = ENOSYS; return NULL; }
    result = original(mapped == NULL ? path : mapped, mode);
    saved = errno; free(mapped); errno = saved; return result;
}

FILE *fopen64(const char *path, const char *mode) {
    typedef FILE *(*function_type)(const char *, const char *);
    static function_type original;
    char *mapped = mc_translate_path(path);
    FILE *result; int saved;
    if (original == NULL) original = (function_type)next_symbol("fopen64");
    if (original == NULL) { free(mapped); errno = ENOSYS; return NULL; }
    result = original(mapped == NULL ? path : mapped, mode);
    saved = errno; free(mapped); errno = saved; return result;
}

#define ONE_PATH_INT(name, args, callargs) \
int name args { \
    typedef int (*function_type) args; static function_type original; \
    char *mapped = mc_translate_path(path); int result, saved; \
    if (original == NULL) original = (function_type)next_symbol(#name); \
    if (original == NULL) { free(mapped); errno = ENOSYS; return -1; } \
    result = original callargs; saved = errno; free(mapped); errno = saved; return result; \
}

ONE_PATH_INT(stat, (const char *path, struct stat *buffer), (mapped == NULL ? path : mapped, buffer))
ONE_PATH_INT(lstat, (const char *path, struct stat *buffer), (mapped == NULL ? path : mapped, buffer))
ONE_PATH_INT(access, (const char *path, int mode), (mapped == NULL ? path : mapped, mode))
ONE_PATH_INT(mkdir, (const char *path, mode_t mode), (mapped == NULL ? path : mapped, mode))
ONE_PATH_INT(unlink, (const char *path), (mapped == NULL ? path : mapped))

ssize_t readlink(const char *path, char *buffer, size_t size) {
    typedef ssize_t (*function_type)(const char *, char *, size_t);
    static function_type original;
    char *mapped = mc_translate_path(path);
    ssize_t result; int saved;
    if (original == NULL) original = (function_type)next_symbol("readlink");
    if (original == NULL) { free(mapped); errno = ENOSYS; return -1; }
    result = original(mapped == NULL ? path : mapped, buffer, size);
    saved = errno; free(mapped); errno = saved; return result;
}

int rename(const char *old_path, const char *new_path) {
    typedef int (*function_type)(const char *, const char *);
    static function_type original;
    char *old_mapped = mc_translate_path(old_path), *new_mapped = mc_translate_path(new_path);
    int result, saved;
    if (original == NULL) original = (function_type)next_symbol("rename");
    if (original == NULL) { free(old_mapped); free(new_mapped); errno = ENOSYS; return -1; }
    result = original(old_mapped == NULL ? old_path : old_mapped, new_mapped == NULL ? new_path : new_mapped);
    saved = errno; free(old_mapped); free(new_mapped); errno = saved; return result;
}

DIR *opendir(const char *path) {
    typedef DIR *(*function_type)(const char *);
    static function_type original;
    char *mapped = mc_translate_path(path);
    DIR *result; int saved;
    if (original == NULL) original = (function_type)next_symbol("opendir");
    if (original == NULL) { free(mapped); errno = ENOSYS; return NULL; }
    result = original(mapped == NULL ? path : mapped);
    saved = errno; free(mapped); errno = saved; return result;
}

void *dlopen(const char *path, int flags) {
    typedef void *(*function_type)(const char *, int);
    static function_type original;
    char *mapped = mc_translate_path(path);
    void *result; int saved;
    if (original == NULL) original = (function_type)next_symbol("dlopen");
    if (original == NULL) { free(mapped); errno = ENOSYS; return NULL; }
    result = original(mapped == NULL ? path : mapped, flags);
    saved = errno; free(mapped); errno = saved; return result;
}

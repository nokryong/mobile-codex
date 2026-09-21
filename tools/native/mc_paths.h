#ifndef MOBILE_CODEX_MC_PATHS_H
#define MOBILE_CODEX_MC_PATHS_H

/* Caller owns a non-NULL returned path. NULL means the original path is kept. */
char *mc_translate_path(const char *path);

#endif

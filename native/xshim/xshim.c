// LD_PRELOAD shim that lets the X server and its clients (Wine's libX11/libxcb, through Box64) work inside the app sandbox.
//  1. link(): the X server takes its display lock with a hard link, which Android's SELinux policy denies to apps.
//  2. "/tmp/.X11-unix" does not exist on Android: redirect it to $ZSHIM_ROOT/tmp/.X11-unix for every path-taking call that the
//     X transport layer uses (mkdir, bind, connect, stat, lstat, access, chmod, unlink).
// Child processes started through /system/bin/sh (bionic) must not inherit LD_PRELOAD, so the variable is dropped at load time
// unless ZSHIM_KEEP is set (Wine processes need the shim in every process that talks to X).
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#define X11DIR "/tmp/.X11-unix"

__attribute__((constructor)) static void xshim_init(void) { if (!getenv("ZSHIM_KEEP")) unsetenv("LD_PRELOAD"); }

static __thread char g_buf[PATH_MAX];

static const char* map(const char* p) {
    if (!p || strncmp(p, X11DIR, sizeof(X11DIR) - 1) != 0) return p;
    const char* root = getenv("ZSHIM_ROOT");
    if (!root) return p;
    snprintf(g_buf, sizeof g_buf, "%s%s", root, p);
    return g_buf;
}

typedef int (*mkdir_fn)(const char*, mode_t);
typedef int (*chmod_fn)(const char*, mode_t);
typedef int (*path_fn)(const char*);
typedef int (*access_fn)(const char*, int);
typedef int (*stat_fn)(const char*, struct stat*);
typedef int (*sock_fn)(int, const struct sockaddr*, socklen_t);
#define REAL(T, name) static T real = NULL; if (!real) real = (T)dlsym(RTLD_NEXT, name)

int link(const char* from, const char* to) {
    (void)from;
    int fd = open(to, O_CREAT | O_EXCL | O_WRONLY, 0600);
    if (fd < 0) return -1;  // keeps EEXIST semantics: the caller then inspects the stale lock
    close(fd);
    return 0;
}

int mkdir(const char* p, mode_t m) { REAL(mkdir_fn, "mkdir"); return real(map(p), m); }
int chmod(const char* p, mode_t m) { REAL(chmod_fn, "chmod"); return real(map(p), m); }
int unlink(const char* p) { REAL(path_fn, "unlink"); return real(map(p)); }
int access(const char* p, int m) { REAL(access_fn, "access"); return real(map(p), m); }
int stat(const char* p, struct stat* s) { REAL(stat_fn, "stat"); return real(map(p), s); }
int lstat(const char* p, struct stat* s) { REAL(stat_fn, "lstat"); return real(map(p), s); }

static int with_addr(int fd, const struct sockaddr* a, socklen_t l, sock_fn fn) {
    if (a && a->sa_family == AF_UNIX && l > sizeof(sa_family_t) + 1) {
        const struct sockaddr_un* u = (const struct sockaddr_un*)a;
        if (u->sun_path[0] == '/' && strncmp(u->sun_path, X11DIR, sizeof(X11DIR) - 1) == 0) {
            struct sockaddr_un m; memset(&m, 0, sizeof m); m.sun_family = AF_UNIX;
            const char* mp = map(u->sun_path); strncpy(m.sun_path, mp, sizeof m.sun_path - 1);
            return fn(fd, (const struct sockaddr*)&m, sizeof m);
        }
    }
    return fn(fd, a, l);
}
int bind(int fd, const struct sockaddr* a, socklen_t l) { REAL(sock_fn, "bind"); return with_addr(fd, a, l, real); }
int connect(int fd, const struct sockaddr* a, socklen_t l) { REAL(sock_fn, "connect"); return with_addr(fd, a, l, real); }

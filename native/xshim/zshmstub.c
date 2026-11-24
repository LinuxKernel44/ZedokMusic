/* x86_64 stub preloaded into Box64 (BOX64_LD_PRELOAD): Wine's winex11 calls XShmCreateImage, whose
 * Box64 wrapper dereferences the NULL result when MIT-SHM is unusable (no SysV shm on Android).
 * Returning NULL ourselves makes winex11 fall back to plain XPutImage. */
void *XShmCreateImage(void *d, void *v, unsigned depth, int fmt, char *data, void *shm, unsigned w, unsigned h) {
    (void)d; (void)v; (void)depth; (void)fmt; (void)data; (void)shm; (void)w; (void)h; return 0;
}

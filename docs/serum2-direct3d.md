# Serum 2 does not start: findings (2026-11-25, OnePlus 15, ZedokMusic 0.16.x)

**Symptom.** Serum 2 (Xfer, `Serum2.vst3`, 2.1.5) loads and shows its 2000+ parameters, but opening its editor kills the plugin host
("The plugin failed to start"). The host log (`<workspace>/Logs/host_<track>_<plugin>.log`) shows:

    err:wgl:egldrv_init_pixel_formats Failed to get any configs from eglChooseConfig
    err:d3d:wined3d_adapter_gl_init Failed to get a GL context for adapter
    (with "no 3D": fixme:winediag:wined3d_select_feature_level None of the requested D3D feature levels is supported)
    BOX64 SIGSEGV ... x64pc=0x1801815be, accessing (nil)        <- Serum dereferences the null D3D11 device

**Cause.** Serum 2's editor needs a Direct3D 11 device (it imports d3d11/d2d1/dwrite/dxgi). Wine implements Direct3D 11 on top of OpenGL
(or Vulkan). This runtime has neither a GPU path nor a software GL, so `D3D11CreateDevice` fails and the plugin does not check the result.
`wined3d renderer=no3d` does not help (no D3D11 feature level).

**What was tried.** Ubuntu 24.04 arm64 Mesa 25.2 (llvmpipe) added to the runtime: libEGL/libglvnd, libEGL_mesa, libgallium, libLLVM 20,
dri/libdril_dri.so (+52 MB in the APK), with `LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe LIBGL_DRIVERS_PATH __EGL_VENDOR_LIBRARY_FILENAMES`.

* A small aarch64 test program (`dlopen("libEGL.so.1")`, X11 platform on Xvfb, run in the app's domain with the rootfs glibc loader)
  gets an EGL 1.5 display, vendor "Mesa Project" and 50 configs, for every surface/renderable combination Wine asks for.
* Inside Wine (through Box64) EGL is found and its client extensions are listed, but `eglInitialize` fails **silently** in
  `init_egl_platform` (win32u/opengl.c: `if (!eglInitialize(...)) return;`), so there are no configs. Mesa prints nothing there, even with
  `EGL_LOG_LEVEL=debug`, which suggests Mesa's initialisation is not reached or the environment is not visible to it.
* Not tried: Wine's `WINEDEBUG=+wgl` with a patched win32u, `BOX64_LOG=2` around `eglInitialize`, `EGL_PLATFORM=surfaceless`, `GALLIUM_DRIVER=softpipe`,
  DXVK + lavapipe, a Vortek/VirGL path to the phone's GPU (what Winlator does).

The Mesa files are kept in `build/rootfs-work/mesa/overlay` (not in the shipped runtime).

**Dev aids added meanwhile.** `files/box64log` (BOX64_LOG value), `files/winedebug` (WINEDEBUG), per-plugin logs in `<workspace>/Logs`,
`BOX64_SHOWSEGV=1`, and a debug build signed with the release key (to use `run-as` and `--es cmd` on a release device).

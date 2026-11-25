# Serum 2 and Direct3D 11 (2026-11-25)

**Resolved in 0.17.0** (software): Wine's Direct3D now runs through DXVK 2.4.1 on Vulkan, with Mesa lavapipe as the Vulkan driver
(see tools/build-gpu-overlay.sh). Serum 2's editor opens on a Galaxy S10. The OpenGL attempt below was abandoned for this route.
A real GPU driver (Turnip on Adreno, or Winlator's Vortek) would replace only the ICD; presenting into Xvfb is the open question.

## Original findings (before 0.17.0): Serum 2 does not start

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

## Phone GPU (Adreno) — 0.18.0

Turnip 26.2.0 (Winlator's build) works under DXVK on the OnePlus 15 (Adreno 840, `/dev/kgsl-3d0` reachable): the Direct3D 11 test program
(`native/testd3d`) sees "Adreno (TM) 840", feature level 12_0, and a CPU read-back of a cleared render target is correct.

**Presenting is the hard part.** The app's X server (Xvfb) has no DRI3, so Mesa's X11 swapchain never frees its images (the third
`vkAcquireNextImageKHR` blocks, with or without MIT-SHM, FIFO or immediate; without `MESA_VK_WSI_DEBUG=sw` swapchain creation itself hangs).
Mesa's *headless* swapchain (`MESA_VK_WSI_HEADLESS_SWAPCHAIN=1`) renders at full speed (1300 fps in the test) but shows nothing, so
`native/gpulayer/zpresent.c` is a Vulkan layer (`VK_LAYER_ZEDOK_present`, loaded with `VK_LAYER_PATH` + `VK_INSTANCE_LAYERS`) that, on each
`vkQueuePresentKHR`, copies the swapchain image into host memory and draws it into the window with XCB `PutImage` strips, and caps the rate at 60 fps
(`ZP_FPS`; `ZP_LOG=<file>` traces). Measured: 59.7 fps, frames visible in the X screen.

Not usable: Winlator's Vortek renderer. Its native part calls back into Winlator's Java X server (`getWindowHardwareBuffer`), and the S10's
Mali has no open driver, so the S10 stays on software Vulkan.

**Serum 2 on the OnePlus 15, still failing (not a GPU problem).** With the full "Serum 2 Presets" folder installed, opening the editor ends in an access
violation inside Serum2.vst3 (RVA 0x1815be: `gPlatformFactory->vfunc()->obj->field` is NULL; VSTGUI code), also with llvmpipe. With the presets folder
absent, the editor opens and draws on the GPU ("Presets Folder Not Found"). The last things Serum does before crashing are probing for
`Skins\Default\theme.json`, `Tables\S2 Tables\Default Shapes.txt` and licence files (all absent); Wine's `DCompositionCreateDevice2` is only a stub.
`dcomp=d` did not help. Next ideas: system fonts (the prefix has only Wine's Tahoma), DirectWrite custom font collections, D2D.

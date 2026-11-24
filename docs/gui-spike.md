# Plugin GUIs: feasibility notes (work in progress, not shipped)

Goal: show a plugin's own editor window. Wine needs an X server to create windows; Android has none.

## What works (verified on a Galaxy S10, Android 12)

An unmodified **Xvfb** from Ubuntu 24.04 arm64 (glibc 2.39, the same glibc as the bundled runtime) runs inside the app:

- Binary patches of equal length (strings are NUL-padded): `/usr/bin`→`.`, `/tmp`→`.`, `/tmp/`→`./`, `/var/lib/xkb/`→`./`, `/bin/sh`→`./sh`,
  and `patchelf` sets the interpreter and RPATH to the app's `files/root`. Run it with its working directory set to `root/usr/bin`,
  where `sh` is a symlink to `/system/bin/sh` and `xkbcomp` lives.
- `-xkbdir <root>/usr/share/X11/xkb -fp built-ins -nolisten tcp -fbdir <dir>`; the framebuffer is a memory-mappable XWD file.
- Two things Android forbids are handled by `native/xshim/xshim.c`, an `LD_PRELOAD` shim built with `aarch64-linux-gnu-gcc`:
  hard links (`link()`, used for the display lock) and the missing `/tmp/.X11-unix` (redirected to `$ZSHIM_ROOT/tmp/.X11-unix`
  for mkdir/bind/connect/stat/...). The shim removes `LD_PRELOAD` from the environment at load time (children started through the
  bionic `/system/bin/sh` would otherwise fail to link) unless `ZSHIM_KEEP` is set.
- A plain Xlib client connects (`XOpenDisplay -> OK`, 1024x768) and `winex11` loads under Box64. Wine's **virtual desktop**
  (`wine explorer /desktop=zedok,1024x768 ...`) renders its blue desktop and cursor into the framebuffer: no window manager needed.

## Solved (2026-11-24): Wine windows now render

Root cause of the "windows never appear" problem: winex11 calls `XShmCreateImage` for every window surface. On Android there is no
SysV shm / usable MIT-SHM, so the call returns NULL, and Box64's wrapper of that function dereferences the NULL result
(`SIGSEGV @ XShmCreateImage+0x13` -> Wine's `handle_syscall_fault`, `CreateWindowEx` returned `0xc0000005`). The window was created
but never mapped. Fix (no Wine rebuild): `native/xshim/zshmstub.c`, a tiny x86_64 library preloaded into Box64 with
`BOX64_LD_PRELOAD=<root>/usr/lib/libzshmstub.so` that makes `XShmCreateImage` return NULL, so winex11 falls back to plain `XPutImage`.
Build with host `gcc -shared -nostdlib -fPIC`. Xvfb is started with `-extension MIT-SHM`.

Verified: a minimal Win32 window (`CreateWindowEx` + `WM_PAINT`) shows up in the Xvfb framebuffer inside
`wine explorer /desktop=zedok,1024x768`. A window manager is not needed in virtual-desktop mode (`native/xshim/zwm.c`, a 60-line
WM, is kept for non-desktop mode but was not required).
Debug recipe: `BOX64_LOG=1 BOX64_SHOWSEGV=1 BOX64_SHOWBT=1` + `WINEDEBUG=+seh,+x11drv,+win`.

## Shipped in 0.3.0

All four steps were built: `zhost.exe` opens the editor on its own GUI thread (`IPlugView::attached(HWND)`, `IPlugFrame::resizeView`,
`IComponentHandler` so knob moves inside the editor reach the DSP and the app's parameter table); the app mirrors the framebuffer
(`xclient.cpp`, ~30 fps) and injects pointer events through XTEST over the X socket; the Xvfb overlay and both shims are built by
`tools/build-gui-overlay.sh` and merged into the rootfs by `tools/build-rootfs.sh`.
Hosts are started as `wine explorer /desktop=zedok,1600x1000 zhost.exe ...` so every editor lands on one shared virtual desktop.
(Setting the desktop in the registry instead gave `BadWindow` for windows whose parent belonged to a dead explorer.)
A stale `wineserver` from an earlier run must be killed before restarting Xvfb (`GuiServer.start`).

Gotchas: u-he plugins look for `<Plugin>.data` next to the `.vst3` (then in the registry) and block on a modal dialog otherwise;
stdout of hosts started through explorer does not reach the app's log file; `WINEDEBUG` for hosts can be set by putting a value in
`files/winedebug` (debug aid).

Ideas: keyboard and multi-touch, one X screen region per plugin so several editors can be open, lower idle CPU (the host spin loop).

## OxygenOS "Security warning" (found on a OnePlus 15, Android 17)

Symptom: a dialog "An attempt by a malicious app to damage the system has been stopped" (shown by `com.oplus.exsystemservice`)
a few seconds after the first plugin host starts. Bisect (always wait for the dialog: it can lag by several seconds, and an
undismissed dialog contaminates the next run): not Wine/Box64, not the shim's preloading, not any single library, not the file name;
`Xvfb -help` alone triggered it. A small ptrace-based syscall counter (build it with `aarch64-linux-gnu-gcc`, patchelf the
interpreter, run it from the debug `--es cmd` hook) showed `setresuid(-1, 0, -1)`: Xvfb calls `seteuid(0)` when it is not root.
The kernel refuses it, but the vendor's guard reports the attempt. Fix: `native/xshim/xshim.c` interposes `setuid/seteuid/setreuid/
setresuid` and the gid variants: a change that keeps the current id returns 0 (Xvfb does `setuid(getuid())` before running
`xkbcomp` and aborts if it fails: "Failed to compile keymap"), anything else returns EPERM without any syscall.
Developer aids: file `files/guimode` = `none` (no X server), `xvfb` (X server only), `kill` (clean-up step only); `files/winedebug`.
Android 15+ also warns about native libraries that are not 16 KB aligned: `-Wl,-z,max-page-size=16384` is set in CMake.

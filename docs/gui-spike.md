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

## What does not work yet

- A normal top-level Windows window never became visible in the framebuffer (without a window manager, managed windows stay unmapped).
- Inside the virtual desktop, `notepad.exe` and `winefile.exe` did not show up either; the cause is not identified (they were not
  running a minute later and no Wine error was logged).

## Still to build once that is solved

1. `zhost.exe`: create the editor with `IPlugView` (`attached(HWND)`), implement `IPlugFrame::resizeView`.
2. Show the framebuffer region in the app (`mmap` the XWD file, ~30 fps).
3. Forward touches to the plugin window from inside `zhost.exe` (`SendInput`/`PostMessage`) through the shared-memory bridge.
4. Package the Xvfb overlay (about 15 MB) in `tools/build-rootfs.sh`.

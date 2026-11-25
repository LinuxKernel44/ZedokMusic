// zshell.exe: a small Windows shell for ZedokMusic's "Windows desktop" (Settings). Wine's explorer gives a bare desktop; this adds
// what a desktop needs: a taskbar with a button per open window, a Start menu with the system tools, and a clock.
// Everything is mouse driven (the app turns touches into mouse events). Build: native/shell/build.sh
#include <windows.h>
#include <commdlg.h>
#include <stdio.h>
#include <string.h>

#define BAR_H 56
#define TIMER_ID 1
enum { CMD_FILES = 1, CMD_REGEDIT, CMD_TASKMGR, CMD_NOTEPAD, CMD_CONTROL, CMD_CONSOLE, CMD_WINECFG, CMD_RUN, CMD_UNINSTALL, CMD_WORDPAD, CMD_INSTALLERS, CMD_VST3, CMD_REFRESH };

static HWND gBar; static HFONT gFont, gBold;
static int gScreenW, gScreenH;
typedef struct { HWND h; char title[80]; RECT r; } Item;
static Item gItems[24]; static int gCount;
static RECT gStart, gClock;

static void run(const char* cmd) {
    STARTUPINFOA si = { sizeof si }; PROCESS_INFORMATION pi; char buf[1024]; snprintf(buf, sizeof buf, "%s", cmd);
    if (CreateProcessA(NULL, buf, NULL, NULL, FALSE, 0, NULL, NULL, &si, &pi)) { CloseHandle(pi.hThread); CloseHandle(pi.hProcess); }
}
static void runFileDialog(const char* startDir) {
    char file[MAX_PATH] = ""; OPENFILENAMEA o = { sizeof o };
    o.hwndOwner = gBar; o.lpstrFilter = "Programs and installers\0*.exe;*.msi;*.bat;*.cmd\0All files\0*.*\0"; o.lpstrFile = file; o.nMaxFile = MAX_PATH;
    o.lpstrInitialDir = startDir; o.lpstrTitle = "Run a program"; o.Flags = OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST | OFN_NOCHANGEDIR;
    if (!GetOpenFileNameA(&o)) return;
    char dir[MAX_PATH]; snprintf(dir, sizeof dir, "%s", file); char* s = strrchr(dir, '\\'); if (s) *s = 0;
    const char* ext = strrchr(file, '.');
    char cmd[1200];
    if (ext && !_stricmp(ext, ".msi")) snprintf(cmd, sizeof cmd, "msiexec /i \"%s\"", file); else snprintf(cmd, sizeof cmd, "\"%s\"", file);
    STARTUPINFOA si = { sizeof si }; PROCESS_INFORMATION pi;
    if (CreateProcessA(NULL, cmd, NULL, NULL, FALSE, 0, NULL, dir, &si, &pi)) { CloseHandle(pi.hThread); CloseHandle(pi.hProcess); }
}
static void openFolder(const char* path) { char c[600]; snprintf(c, sizeof c, "winefile \"%s\"", path); run(c); }

static BOOL CALLBACK enumWin(HWND h, LPARAM l) {
    if (h == gBar || !IsWindowVisible(h) || GetWindow(h, GW_OWNER)) return TRUE;
    if (GetWindowLongA(h, GWL_EXSTYLE) & WS_EX_TOOLWINDOW) return TRUE;
    char t[80]; if (!GetWindowTextA(h, t, sizeof t) || !t[0]) return TRUE;
    if (gCount < 24) { gItems[gCount].h = h; snprintf(gItems[gCount].title, 80, "%s", t); gCount++; }
    return TRUE;
}
static void refresh(void) {
    Item old[24]; int oc = gCount; memcpy(old, gItems, sizeof old); gCount = 0;
    EnumWindows(enumWin, 0);
    int changed = oc != gCount;
    for (int i = 0; !changed && i < gCount; i++) if (old[i].h != gItems[i].h || strcmp(old[i].title, gItems[i].title)) changed = 1;
    if (changed) InvalidateRect(gBar, NULL, TRUE);
}

static void paint(HWND h) {
    PAINTSTRUCT ps; HDC dc = BeginPaint(h, &ps); RECT cr; GetClientRect(h, &cr);
    HBRUSH bg = CreateSolidBrush(RGB(24, 27, 33)); FillRect(dc, &cr, bg); DeleteObject(bg);
    SetBkMode(dc, TRANSPARENT);
    HBRUSH line = CreateSolidBrush(RGB(61, 220, 196)); RECT top = { 0, 0, cr.right, 2 }; FillRect(dc, &top, line); DeleteObject(line);
    // Start
    gStart.left = 6; gStart.top = 8; gStart.right = 130; gStart.bottom = BAR_H - 6;
    HBRUSH sb = CreateSolidBrush(RGB(30, 80, 72)); FillRect(dc, &gStart, sb); DeleteObject(sb);
    SelectObject(dc, gBold); SetTextColor(dc, RGB(61, 220, 196)); DrawTextA(dc, "Start", -1, &gStart, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
    // clock
    SYSTEMTIME st; GetLocalTime(&st); char tm[16]; snprintf(tm, sizeof tm, "%02d:%02d", st.wHour, st.wMinute);
    gClock.left = cr.right - 90; gClock.top = 8; gClock.right = cr.right - 6; gClock.bottom = BAR_H - 6;
    SelectObject(dc, gFont); SetTextColor(dc, RGB(200, 205, 214)); DrawTextA(dc, tm, -1, &gClock, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
    // windows
    int x = gStart.right + 8, avail = gClock.left - x - 6; int n = gCount ? gCount : 1; int w = avail / n; if (w > 220) w = 220; if (w < 70) w = 70;
    HWND fg = GetForegroundWindow();
    for (int i = 0; i < gCount; i++) {
        RECT r = { x, 8, x + w - 4, BAR_H - 6 }; gItems[i].r = r; if (r.right > gClock.left) { gItems[i].r.left = gItems[i].r.right = 0; continue; }
        int active = gItems[i].h == fg && !IsIconic(gItems[i].h);
        HBRUSH b = CreateSolidBrush(active ? RGB(48, 56, 70) : RGB(36, 41, 51)); FillRect(dc, &r, b); DeleteObject(b);
        RECT tr = { r.left + 8, r.top, r.right - 6, r.bottom }; SetTextColor(dc, active ? RGB(236, 238, 242) : RGB(152, 160, 174));
        DrawTextA(dc, gItems[i].title, -1, &tr, DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);
        x += w;
    }
    EndPaint(h, &ps);
}

static void startMenu(HWND h) {
    HMENU m = CreatePopupMenu();
    AppendMenuA(m, MF_STRING, CMD_FILES, "File manager");
    AppendMenuA(m, MF_STRING, CMD_REGEDIT, "Registry editor");
    AppendMenuA(m, MF_STRING, CMD_TASKMGR, "Task manager");
    AppendMenuA(m, MF_STRING, CMD_CONSOLE, "Command prompt");
    AppendMenuA(m, MF_STRING, CMD_NOTEPAD, "Notepad");
    AppendMenuA(m, MF_STRING, CMD_WORDPAD, "WordPad");
    AppendMenuA(m, MF_STRING, CMD_CONTROL, "Control panel");
    AppendMenuA(m, MF_STRING, CMD_UNINSTALL, "Uninstall programs");
    AppendMenuA(m, MF_STRING, CMD_WINECFG, "Wine configuration");
    AppendMenuA(m, MF_SEPARATOR, 0, NULL);
    AppendMenuA(m, MF_STRING, CMD_RUN, "Run a program or installer...");
    AppendMenuA(m, MF_STRING, CMD_INSTALLERS, "Open the Installers folder");
    AppendMenuA(m, MF_STRING, CMD_VST3, "Open the VST3 folder");
    POINT p = { gStart.left, 0 }; ClientToScreen(h, &p);
    SetForegroundWindow(h);
    int cmd = TrackPopupMenu(m, TPM_RETURNCMD | TPM_BOTTOMALIGN | TPM_LEFTALIGN, p.x, p.y, 0, h, NULL);
    DestroyMenu(m);
    switch (cmd) {
    case CMD_FILES: run("winefile"); break;
    case CMD_REGEDIT: run("regedit"); break;
    case CMD_TASKMGR: run("taskmgr"); break;
    case CMD_CONSOLE: run("wineconsole cmd"); break;
    case CMD_NOTEPAD: run("notepad"); break;
    case CMD_WORDPAD: run("wordpad"); break;
    case CMD_CONTROL: run("control"); break;
    case CMD_UNINSTALL: run("uninstaller"); break;
    case CMD_WINECFG: run("winecfg"); break;
    case CMD_RUN: runFileDialog("D:\\"); break;
    case CMD_INSTALLERS: openFolder("D:\\Installers"); break;
    case CMD_VST3: openFolder("C:\\Program Files\\Common Files\\VST3"); break;
    }
}

static LRESULT CALLBACK proc(HWND h, UINT m, WPARAM w, LPARAM l) {
    switch (m) {
    case WM_PAINT: paint(h); return 0;
    case WM_ERASEBKGND: return 1;
    case WM_TIMER: refresh(); { static int tick; if (++tick % 20 == 0) InvalidateRect(h, NULL, FALSE); } return 0;
    case WM_MOUSEACTIVATE: return MA_NOACTIVATE;
    case WM_LBUTTONDOWN: {
        POINT p = { (short)LOWORD(l), (short)HIWORD(l) };
        if (PtInRect(&gStart, p)) { startMenu(h); return 0; }
        for (int i = 0; i < gCount; i++) if (gItems[i].r.right > gItems[i].r.left && PtInRect(&gItems[i].r, p)) {
            HWND t = gItems[i].h;
            if (IsIconic(t)) { ShowWindow(t, SW_RESTORE); SetForegroundWindow(t); }
            else if (GetForegroundWindow() == t) ShowWindow(t, SW_MINIMIZE);
            else { BringWindowToTop(t); SetForegroundWindow(t); }
            InvalidateRect(h, NULL, TRUE); return 0;
        }
        return 0; }
    case WM_DESTROY: PostQuitMessage(0); return 0;
    }
    return DefWindowProcA(h, m, w, l);
}

int WINAPI WinMain(HINSTANCE inst, HINSTANCE prev, LPSTR cmd, int show) {
    gScreenW = GetSystemMetrics(SM_CXSCREEN); gScreenH = GetSystemMetrics(SM_CYSCREEN);
    gFont = CreateFontA(-20, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, "Tahoma");
    gBold = CreateFontA(-22, 0, 0, 0, FW_BOLD, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, "Tahoma");
    WNDCLASSA wc = { 0 }; wc.lpfnWndProc = proc; wc.hInstance = inst; wc.lpszClassName = "ZedokShellBar"; wc.hCursor = LoadCursor(NULL, IDC_ARROW);
    RegisterClassA(&wc);
    gBar = CreateWindowExA(WS_EX_TOOLWINDOW | WS_EX_TOPMOST, "ZedokShellBar", "Taskbar", WS_POPUP | WS_VISIBLE, 0, gScreenH - BAR_H, gScreenW, BAR_H, NULL, NULL, inst, NULL);
    RECT wa = { 0, 0, gScreenW, gScreenH - BAR_H }; SystemParametersInfoA(SPI_SETWORKAREA, 0, &wa, SPIF_SENDCHANGE);  // maximised windows stop above the bar
    SetTimer(gBar, TIMER_ID, 500, NULL);
    run("winefile");  // something to see and use on first open
    MSG msg; while (GetMessageA(&msg, NULL, 0, 0)) { TranslateMessage(&msg); DispatchMessageA(&msg); }
    return 0;
}

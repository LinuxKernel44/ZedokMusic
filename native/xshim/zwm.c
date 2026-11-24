/* zwm: minimal X11 window manager for ZedokMusic's embedded Xvfb.
 * Wine's winex11 expects a WM to map managed windows. This one maps every
 * requested window, honours configure requests and advertises EWMH support. */
#include <X11/Xlib.h>
#include <X11/Xatom.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static int onerr(Display *d, XErrorEvent *e) { (void)d; (void)e; return 0; }

int main(int argc, char **argv) {
    Display *d = XOpenDisplay(argc > 1 ? argv[1] : NULL);
    if (!d) { fprintf(stderr, "zwm: cannot open display\n"); return 1; }
    Window root = DefaultRootWindow(d);
    XSetErrorHandler(onerr);
    XSelectInput(d, root, SubstructureRedirectMask | SubstructureNotifyMask);
    XSync(d, False);
    Window chk = XCreateSimpleWindow(d, root, -10, -10, 1, 1, 0, 0, 0);
    Atom a_chk = XInternAtom(d, "_NET_SUPPORTING_WM_CHECK", False);
    Atom a_sup = XInternAtom(d, "_NET_SUPPORTED", False);
    Atom a_name = XInternAtom(d, "_NET_WM_NAME", False);
    Atom a_utf = XInternAtom(d, "UTF8_STRING", False);
    Atom sup[] = { a_chk, XInternAtom(d, "_NET_WM_STATE", False), XInternAtom(d, "_NET_ACTIVE_WINDOW", False),
                   XInternAtom(d, "_NET_WM_WINDOW_TYPE", False), XInternAtom(d, "_NET_WORKAREA", False) };
    XChangeProperty(d, root, a_chk, XA_WINDOW, 32, PropModeReplace, (unsigned char *)&chk, 1);
    XChangeProperty(d, chk, a_chk, XA_WINDOW, 32, PropModeReplace, (unsigned char *)&chk, 1);
    XChangeProperty(d, chk, a_name, a_utf, 8, PropModeReplace, (unsigned char *)"zwm", 3);
    XChangeProperty(d, root, a_sup, XA_ATOM, 32, PropModeReplace, (unsigned char *)sup, 5);
    XSync(d, False);
    fprintf(stderr, "zwm: running\n");
    for (;;) {
        XEvent e; XNextEvent(d, &e);
        if (e.type == MapRequest) {
            XMapWindow(d, e.xmaprequest.window);
        } else if (e.type == ConfigureRequest) {
            XConfigureRequestEvent *c = &e.xconfigurerequest; XWindowChanges ch;
            ch.x = c->x; ch.y = c->y; ch.width = c->width; ch.height = c->height;
            ch.border_width = c->border_width; ch.sibling = c->above; ch.stack_mode = c->detail;
            XConfigureWindow(d, c->window, c->value_mask, &ch);
        }
    }
}

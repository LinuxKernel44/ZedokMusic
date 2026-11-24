package com.zedokmusic.engine

import android.graphics.Bitmap

/** The X screen that Wine draws plugin editors on: framebuffer access and pointer injection (see native xclient.cpp). */
object XDisplay {
    init { System.loadLibrary("zedokaudio") }
    external fun fbOpen(path: String): Boolean
    external fun fbSize(): IntArray
    external fun fbBlit(bitmap: Bitmap, x: Int, y: Int, w: Int, h: Int): Boolean
    external fun inputConnect(socketPath: String): Boolean
    external fun inputClose()
    /** [action]: 0 move, 1 button down, 2 button up; [button] 1 left, 2 middle, 3 right, 4/5 wheel up/down; coordinates in screen pixels. */
    external fun pointer(action: Int, x: Int, y: Int, button: Int): Boolean
    /** Types a Unicode character; false when the X keyboard has no such key. */
    external fun typeChar(codePoint: Int): Boolean
    /** Taps a key by X keysym (BackSpace 0xff08, Return 0xff0d, Tab 0xff09, Escape 0xff1b, arrows 0xff51-0xff54). */
    external fun tapKeysym(keysym: Int): Boolean
}

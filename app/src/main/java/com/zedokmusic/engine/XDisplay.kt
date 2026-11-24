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
    /** [action]: 0 move, 1 button down, 2 button up; coordinates in screen pixels. */
    external fun pointer(action: Int, x: Int, y: Int): Boolean
}

package com.zedokmusic

import android.content.Intent
import java.io.File

/** Release builds have no debug hooks: nothing here can be triggered from outside the app. */
object DebugHooks {
    @Suppress("UNUSED_PARAMETER")
    fun autoFolder(intent: Intent?): File? = null
    @Suppress("UNUSED_PARAMETER")
    fun install(activity: MainActivity, intent: Intent?) {}
}

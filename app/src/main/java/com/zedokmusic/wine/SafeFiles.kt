package com.zedokmusic.wine

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Deletes a folder and what is inside it WITHOUT ever following a symbolic link: a link is removed, never what it points to.
 *
 * Kotlin's File.deleteRecursively() follows links to folders. The Wine prefix holds `dosdevices/d:` -> the user's whole ZedokMusic
 * folder and `e:` -> the app's own files, so deleting a prefix with it erased the user's projects, installers and plugins.
 * Every recursive delete of a folder that may contain links must go through here.
 */
fun File.deleteTree(): Boolean {
    val start = toPath()
    if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return true
    return try {
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.deleteIfExists(file); return FileVisitResult.CONTINUE }  // regular files and links alike
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult { Files.deleteIfExists(dir); return FileVisitResult.CONTINUE }
        })  // walkFileTree does not follow links unless asked to (FOLLOW_LINKS)
        true
    } catch (e: IOException) { false }
}

package cn.enaium.battalion.util

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.createDirectories
import io.github.vinceglb.filekit.isDirectory
import io.github.vinceglb.filekit.list
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.parent
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.readString
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.runBlocking

/**
 * Synchronous file I/O backed by FileKit (https://github.com/vinceglb/FileKit).
 *
 * FileKit's read/write APIs are suspend functions while the game loop is fully synchronous,
 * so every call is wrapped in [runBlocking]. All methods return null / false on failure rather
 * than throwing, which keeps asset loading defensive (a missing optional file is not fatal).
 */
object Fio {

    fun readBytes(path: String): ByteArray? = runBlocking {
        try {
            PlatformFile(path).readBytes()
        } catch (t: Throwable) {
            null
        }
    }

    fun readText(path: String): String? = runBlocking {
        try {
            PlatformFile(path).readString()
        } catch (t: Throwable) {
            null
        }
    }

    /** Lists directory entry names, or null if the directory does not exist. */
    fun listDir(path: String): List<String>? = runBlocking {
        try {
            val dir = PlatformFile(path)
            if (!dir.isDirectory()) return@runBlocking null
            dir.list().map { it.name }
        } catch (t: Throwable) {
            null
        }
    }

    /** Writes text (overwrites), creating parent directories. Returns success. */
    fun writeText(path: String, text: String): Boolean = runBlocking {
        try {
            val file = PlatformFile(path)
            file.parent()?.createDirectories()
            file.writeString(text)
            true
        } catch (t: Throwable) {
            false
        }
    }
}
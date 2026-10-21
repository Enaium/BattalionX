package cn.enaium.battalion.util

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.createDirectories
import io.github.vinceglb.filekit.exists
import io.github.vinceglb.filekit.isDirectory
import io.github.vinceglb.filekit.list
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.parent
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.readString
import io.github.vinceglb.filekit.write
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.runBlocking

/** Native (macOS/Linux) file I/O, backed by FileKit. */
actual object Fio {
    actual fun readBytes(path: String): ByteArray? = runBlocking {
        try {
            PlatformFile(path).readBytes()
        } catch (t: Throwable) {
            null
        }
    }

    actual fun readText(path: String): String? = runBlocking {
        try {
            PlatformFile(path).readString()
        } catch (t: Throwable) {
            null
        }
    }

    actual fun exists(path: String): Boolean = runBlocking {
        try {
            PlatformFile(path).exists()
        } catch (t: Throwable) {
            false
        }
    }

    actual fun listDir(path: String): List<String>? = runBlocking {
        try {
            val dir = PlatformFile(path)
            if (!dir.isDirectory()) return@runBlocking null
            dir.list().map { it.name }
        } catch (t: Throwable) {
            null
        }
    }

    actual fun writeText(path: String, text: String): Boolean = runBlocking {
        try {
            val file = PlatformFile(path)
            file.parent()?.createDirectories()
            file.writeString(text)
            true
        } catch (t: Throwable) {
            false
        }
    }

    actual fun writeBytes(path: String, bytes: ByteArray): Boolean = runBlocking {
        try {
            val file = PlatformFile(path)
            file.parent()?.createDirectories()
            file write bytes
            true
        } catch (t: Throwable) {
            false
        }
    }
}

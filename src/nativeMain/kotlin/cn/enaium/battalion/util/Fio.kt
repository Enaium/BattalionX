package cn.enaium.battalion.util

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * Native (macOS/Linux/Windows) file I/O, backed by kotlinx-io. (FileKit dropped macOS x64 in
 * 0.14, which the release matrix still ships.)
 */
actual object Fio {
    private fun path(path: String) = Path(path)

    actual fun readBytes(path: String): ByteArray? = try {
        SystemFileSystem.source(path(path)).buffered().use { it.readByteArray() }
    } catch (t: Throwable) {
        null
    }

    actual fun readText(path: String): String? = try {
        SystemFileSystem.source(path(path)).buffered().use { it.readString() }
    } catch (t: Throwable) {
        null
    }

    actual fun exists(path: String): Boolean = try {
        SystemFileSystem.exists(path(path))
    } catch (t: Throwable) {
        false
    }

    actual fun listDir(path: String): List<String>? = try {
        val dir = path(path)
        if (SystemFileSystem.metadataOrNull(dir)?.isDirectory != true) null
        else SystemFileSystem.list(dir).map { it.name }
    } catch (t: Throwable) {
        null
    }

    actual fun writeText(path: String, text: String): Boolean = try {
        val file = path(path)
        file.parent?.let { SystemFileSystem.createDirectories(it) }
        SystemFileSystem.sink(file).buffered().use { it.writeString(text) }
        true
    } catch (t: Throwable) {
        false
    }

    actual fun writeBytes(path: String, bytes: ByteArray): Boolean = try {
        val file = path(path)
        file.parent?.let { SystemFileSystem.createDirectories(it) }
        SystemFileSystem.sink(file).buffered().use { it.write(bytes) }
        true
    } catch (t: Throwable) {
        false
    }
}

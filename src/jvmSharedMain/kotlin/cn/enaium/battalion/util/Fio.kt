package cn.enaium.battalion.util

import java.io.File

/** JVM/Android file I/O. Android's external files directory is a normal filesystem path. */
actual object Fio {
    actual fun readBytes(path: String): ByteArray? = try {
        File(path).readBytes()
    } catch (t: Throwable) {
        null
    }

    actual fun readText(path: String): String? = try {
        File(path).readText()
    } catch (t: Throwable) {
        null
    }

    actual fun exists(path: String): Boolean = File(path).exists()

    actual fun listDir(path: String): List<String>? = try {
        val dir = File(path)
        if (!dir.isDirectory) null else dir.list()?.toList()
    } catch (t: Throwable) {
        null
    }

    actual fun writeText(path: String, text: String): Boolean = try {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(text)
        true
    } catch (t: Throwable) {
        false
    }

    actual fun writeBytes(path: String, bytes: ByteArray): Boolean = try {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        true
    } catch (t: Throwable) {
        false
    }
}

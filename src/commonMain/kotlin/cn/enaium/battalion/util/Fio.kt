package cn.enaium.battalion.util

/**
 * Synchronous file I/O used by the data/asset/mod/audio layers.
 *
 * The game loop is synchronous, so this is a small blocking API rather than a coroutine one.
 * JVM and Android use `java.io.File`; the native targets keep FileKit (the only platform
 * where it was needed for path handling). All methods return null / false on failure rather
 * than throwing, which keeps asset loading defensive (a missing optional file is not fatal).
 */
expect object Fio {
    fun readBytes(path: String): ByteArray?
    fun readText(path: String): String?
    fun exists(path: String): Boolean

    /** Lists directory entry names, or null if the directory does not exist. */
    fun listDir(path: String): List<String>?
    fun writeText(path: String, text: String): Boolean
    fun writeBytes(path: String, bytes: ByteArray): Boolean
}

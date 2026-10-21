package cn.enaium.battalion.game.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import cn.enaium.battalion.AndroidPlatform
import cn.enaium.sdl.SDLColor
import kotlin.math.ceil

actual fun decodeRgbaImage(path: String): RgbaImage? {
    val bitmap = try {
        BitmapFactory.decodeFile(path)
    } catch (t: Throwable) {
        null
    } ?: return null
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    bitmap.recycle()
    val out = ByteArray(w * h * 4)
    for (i in pixels.indices) {
        val p = pixels[i]
        out[i * 4] = ((p shr 16) and 0xff).toByte()
        out[i * 4 + 1] = ((p shr 8) and 0xff).toByte()
        out[i * 4 + 2] = (p and 0xff).toByte()
        out[i * 4 + 3] = ((p ushr 24) and 0xff).toByte()
    }
    return RgbaImage(w, h, out)
}

actual class TextRasterizer actual constructor() {
    actual val superSample: Int = 2

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        color = Color.WHITE
    }

    actual fun measure(text: String, sizePx: Int): Int {
        paint.textSize = (sizePx * superSample).toFloat()
        return ceil(paint.measureText(text) / superSample).toInt()
    }

    actual fun render(text: String, sizePx: Int, color: SDLColor): RgbaText? {
        if (text.isEmpty()) return null
        paint.textSize = (sizePx * superSample).toFloat()
        val width = ceil(paint.measureText(text)).toInt().coerceAtLeast(1)
        val metrics = paint.fontMetrics
        val height = ceil(metrics.descent - metrics.ascent).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawText(text, 0f, -metrics.ascent, paint)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        val out = ByteArray(width * height * 4)
        val r = color.r
        val g = color.g
        val b = color.b
        for (i in pixels.indices) {
            val alpha = (pixels[i] ushr 24) and 0xff
            out[i * 4] = r.toByte()
            out[i * 4 + 1] = g.toByte()
            out[i * 4 + 2] = b.toByte()
            out[i * 4 + 3] = alpha.toByte()
        }
        return RgbaText(width, height, out)
    }

    actual fun close() = Unit
}

actual fun createAudioBackend(): AudioBackend? {
    AndroidPlatform.context ?: return null
    val soundPool = try {
        SoundPool.Builder()
            .setMaxStreams(8)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
    } catch (t: Throwable) {
        return null
    }
    val soundIds = HashMap<String, Int>()
    val loaded = HashSet<Int>()
    soundPool.setOnLoadCompleteListener { _, sampleId, status ->
        if (status == 0) loaded.add(sampleId)
    }
    var music: MediaPlayer? = null

    return object : AudioBackend {
        private fun soundId(path: String): Int? {
            soundIds[path]?.let { return it }
            return try {
                val id = soundPool.load(path, 1)
                if (id > 0) {
                    soundIds[path] = id
                    id
                } else null
            } catch (t: Throwable) {
                null
            }
        }

        override fun playSound(path: String, volume: Float) {
            val id = soundId(path) ?: return
            // SoundPool.load is asynchronous; a sound requested before decoding finishes is
            // dropped (the next request plays it) instead of blocking the game loop.
            if (id !in loaded) return
            soundPool.play(id, volume, volume, 1, 0, 1f)
        }

        override fun playMusic(path: String, volume: Float) {
            stopMusic()
            val player = MediaPlayer()
            try {
                player.setDataSource(path)
                player.isLooping = true
                player.setVolume(volume, volume)
                player.setOnPreparedListener {
                    if (music === player) it.start()
                }
                player.prepareAsync()
                music = player
            } catch (t: Throwable) {
                try {
                    player.release()
                } catch (ignored: Throwable) {
                }
            }
        }

        override fun stopMusic() {
            val player = music ?: return
            music = null
            try {
                if (player.isPlaying) player.stop()
            } catch (t: Throwable) {
                // not prepared / already stopped
            }
            try {
                player.release()
            } catch (t: Throwable) {
                // ignore
            }
        }

        override fun stopAll() {
            stopMusic()
            try {
                soundPool.autoPause()
            } catch (t: Throwable) {
                // ignore
            }
        }

        override fun close() {
            stopAll()
            soundIds.clear()
            loaded.clear()
            try {
                soundPool.release()
            } catch (t: Throwable) {
                // ignore
            }
        }
    }
}

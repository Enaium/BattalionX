package cn.enaium.battalion.game.ui

import android.graphics.Bitmap
import cn.enaium.sdl.SDLSurface
import java.io.FileOutputStream

actual fun saveSurfacePng(surface: SDLSurface, path: String): Boolean = try {
    val w = surface.width
    val h = surface.height
    val pitch = surface.pitch
    val pixels = surface.pixels
    val argb = IntArray(w * h)
    for (y in 0 until h) {
        var src = y * pitch
        var dst = y * w
        for (x in 0 until w) {
            val r = pixels[src].toInt() and 0xff
            val g = pixels[src + 1].toInt() and 0xff
            val b = pixels[src + 2].toInt() and 0xff
            val a = pixels[src + 3].toInt() and 0xff
            argb[dst] = (a shl 24) or (r shl 16) or (g shl 8) or b
            src += 4
            dst++
        }
    }
    val bitmap = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
    FileOutputStream(path).use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    bitmap.recycle()
    true
} catch (t: Throwable) {
    false
}

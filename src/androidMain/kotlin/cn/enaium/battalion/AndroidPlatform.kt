package cn.enaium.battalion

import android.content.Context

/**
 * Pieces of the Android host the shared code needs: the application context for audio
 * (SoundPool/MediaPlayer). Initialized by `MainActivity` before the game loop starts.
 */
object AndroidPlatform {
    var context: Context? = null
        private set

    fun init(context: Context) {
        this.context = context.applicationContext
    }
}

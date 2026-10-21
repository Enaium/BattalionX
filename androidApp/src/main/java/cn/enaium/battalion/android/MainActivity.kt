package cn.enaium.battalion.android

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import cn.enaium.battalion.AndroidPlatform
import cn.enaium.battalion.data.AssetRoot
import cn.enaium.battalion.game.BattalionGame
import cn.enaium.battalion.mod.ModRegistry
import cn.enaium.sdl.SDL
import org.libsdl.app.SDLActivity
import java.io.File

/**
 * Android launcher for BattalionX.
 *
 * The game is pure Kotlin/JVM code driving SDL3 through sdl-kmp's JNI bridge: SDLActivity
 * loads `sdl_jni` (SDL3 + the bridge, packaged from the sdl-kmp Android artifacts) and calls
 * [main] on SDL's dedicated thread. The game's assets are embedded into the shared module at
 * build time (`cn.enaium.embedresource`) and materialized into `filesDir/assets` on first run.
 *
 * The activity is locked to landscape in the manifest; `configChanges` keeps the SDL surface
 * alive across the system's orientation/layout events.
 */
class MainActivity : SDLActivity() {

    override fun getLibraries(): Array<String> = arrayOf("sdl_jni")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // SDL's own activity setup runs after onCreate; re-apply once it settled.
        window.decorView.postDelayed({ hideSystemBars() }, 300)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /**
     * Full-screen landscape: the game canvas is the whole surface, so the status/navigation
     * bars are hidden (they come back with a swipe, then hide again).
     */
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        }
    }

    override fun main() {
        try {
            AndroidPlatform.init(applicationContext)
            val tag = packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString()
            val assetsDir = AssetRoot.resolve(
                assetsDir = File(filesDir, "assets").absolutePath,
                writableDir = filesDir.absolutePath,
                versionTag = tag,
            )
            // Mods live beside the assets on desktop; on Android use the app's external files
            // directory (/sdcard/Android/data/<pkg>/files/mods) so they can be installed with
            // adb or a file manager without root.
            val modsDir = File(getExternalFilesDir(null) ?: filesDir, "mods")
            modsDir.mkdirs()
            ModRegistry.modsRoot = modsDir.absolutePath
            Log.i(TAG, "assets: $assetsDir, mods: ${ModRegistry.modsRoot}")
            BattalionGame(assetsDir).run()
        } catch (t: Throwable) {
            Log.e(TAG, "BattalionX failed", t)
        } finally {
            SDL.quit()
        }
    }

    private companion object {
        const val TAG = "BattalionX"
    }
}

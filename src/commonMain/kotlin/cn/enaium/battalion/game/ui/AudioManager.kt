package cn.enaium.battalion.game.ui

import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.game.Trigger
import cn.enaium.battalion.game.TriggerManager
import cn.enaium.battalion.game.triggers.AttackUnitTrigger
import cn.enaium.battalion.game.triggers.BuildUnitTrigger
import cn.enaium.battalion.game.triggers.CaptureTerrainTrigger
import cn.enaium.battalion.game.triggers.ExtractResourcesTrigger
import cn.enaium.battalion.game.triggers.KillUnitTrigger
import cn.enaium.battalion.game.triggers.MoveUnitTrigger
import cn.enaium.battalion.game.triggers.PlayerTrigger
import cn.enaium.battalion.game.triggers.UpgradeUnitTrigger
import cn.enaium.battalion.util.Fio
import cn.enaium.sdl.SDLAudioDeviceID
import cn.enaium.sdl.mixer.SDLMixer

/**
 * Plays the game's sound effects through SDL_mixer.
 *
 * The unpacked SWF keeps every sound as an MP3 under the `sounds/` directory, named like
 * `217_ExplosionSound_ExplosionSound.mp3` (a numeric id, then the event name). We scan that
 * directory, index the files by their event name, and play the right one whenever the matching
 * [Trigger] fires.
 *
 * Sound playback is fire-and-forget: a fresh [cn.enaium.sdl.mixer.SDLTrack] is created per
 * play so overlapping effects (several units firing in one turn) mix naturally. Because loading
 * audio is comparatively slow, loaded [cn.enaium.sdl.mixer.SDLAudio]s are cached by name.
 */
class AudioManager(private val assetsDir: String) {

    /** The SDL_mixer device everything plays through (null until [load]). */
    private var mixer: cn.enaium.sdl.mixer.SDLMixerDevice? = null

    /** event name -> audio handle (loaded lazily on first play). */
    private val cache = mutableMapOf<String, cn.enaium.sdl.mixer.SDLAudio>()

    /** event name -> file path, discovered by scanning the sounds directory. */
    private val soundFiles = mutableMapOf<String, String>()

    /** Whether audio is available (init failed -> silently no-op). */
    private var available = false

    /** The currently playing looping background-music track (one at a time). */
    private var musicTrack: cn.enaium.sdl.mixer.SDLTrack? = null

    /** The name of the soundtrack currently looping (to avoid restarts that don't change it). */
    private var currentMusic: String? = null

    /** Looping background soundtracks: the player-side ("good") and enemy-side ("evil") loops. */
    private companion object {
        const val PLAYER_LOOP = "PlayerLoopWAV_3"
        const val ENEMY_LOOP = "AILoopWAV_3"
        const val MUSIC_GAIN = 0.5f
    }

    /**
     * Scans the `sounds/` directory and subscribes to the game's triggers. Safe to call once
     * before the game starts; if SDL_mixer can't initialise (no audio device), the manager
     * simply stays silent.
     */
    fun load() {
        if (!SDLMixer.init()) {
            available = false
            return
        }
        mixer = try {
            SDLMixer.createMixerDevice(SDLAudioDeviceID.DEFAULT_PLAYBACK)
        } catch (t: Throwable) {
            SDLMixer.quit()
            available = false
            return
        }
        available = true
        indexSounds()
        subscribe()
    }

    /** Indexes every MP3 in the sounds directory by its event name. */
    private fun indexSounds() {
        val dir = "$assetsDir/sounds"
        for (file in Fio.listDir(dir).orEmpty()) {
            if (!file.endsWith(".mp3")) continue
            // The exported filename is `<id>_<name>_<name>.mp3` where the meaningful event
            // name is the repeated segment. Splitting on '_' and taking the tail is wrong
            // when the name itself contains underscores: "419_PlayerLoopWAV_3_...mp3" would
            // resolve to "3" instead of "PlayerLoopWAV_3". Strip the leading `<id>_` and the
            // doubled name, like TextureManager does: name = the first half of the remainder.
            val rest = file.substringAfter('_').removeSuffix(".mp3") // "PlayerLoopWAV_3_PlayerLoopWAV_3"
            val half = rest.length / 2
            val secondHalfStart = (rest.length + 1) / 2
            val name = if (rest.length >= 2 && rest.substring(0, half) == rest.substring(secondHalfStart)) {
                rest.substring(0, half)
            } else {
                // Fallback: last underscore segment (single-word names like "UIWAV").
                rest.substringAfterLast('_')
            }
            if (name.isNotEmpty()) soundFiles[name] = "$dir/$file"
        }
    }

    /** Subscribes the manager to the gameplay triggers that map to sounds. */
    private fun subscribe() {
        TriggerManager.addListener("killUnit") { t ->
            if (t is KillUnitTrigger) play("ExplosionSound")
        }
        TriggerManager.addListener("buildUnit") { t ->
            if (t is BuildUnitTrigger) play("ConstructUnit")
        }
        TriggerManager.addListener("captureTerrain") { t ->
            if (t is CaptureTerrainTrigger) play("Capture")
        }
        TriggerManager.addListener("extractUnit") { t ->
            if (t is ExtractResourcesTrigger) play("ExtractResources")
        }
        TriggerManager.addListener("upgradeUnit") { t ->
            if (t is UpgradeUnitTrigger) play("ConstructUnit")
        }
        TriggerManager.addListener("moveUnit") { t ->
            if (t is MoveUnitTrigger) playMoveSound(t.unit.unitData.name)
        }
        TriggerManager.addListener("attackUnit") { t ->
            if (t is AttackUnitTrigger) playAttackSound(t.unit.unitData.name)
        }
        TriggerManager.addListener(PlayerTrigger.START_TURN) {
            // Start (or switch) the background music for whose turn it is, then play the
            // turn-start jingle.
            val active = GameManager.instance.activeController
            val allied = active != null && GameManager.instance.isAlliedToThePlayer(active)
            startMusic(if (allied) PLAYER_LOOP else ENEMY_LOOP)
            play("StartTurn")
        }
        TriggerManager.addListener(PlayerTrigger.END_GAME) { t ->
            if (t is PlayerTrigger) play("VictoryWAV")
        }
    }

    /** Picks a movement sound for the given unit type name. */
    private fun playMoveSound(unitName: String) {
        val key = when {
            unitName.contains("Tank") -> "TankMove1"
            unitName.contains("Blimp") -> "BlimpMove"
            unitName.contains("Boat") || unitName.contains("Ship") ||
                unitName.contains("Submarine") || unitName.contains("Uboat") -> "ShipMove"
            unitName.contains("Aircraft") || unitName.contains("Fighter") ||
                unitName.contains("Bomber") -> "AircraftMove"
            unitName.contains("Commando") -> "CommandoMove1"
            else -> "VehicleMove"
        }
        play(key)
    }

    /** Picks a firing sound for the given unit type name. */
    private fun playAttackSound(unitName: String) {
        val key = when {
            unitName.contains("Scorpion") -> "FireScorpionTank"
            unitName.contains("Flak") -> "FireFlakTank"
            unitName.contains("Turret") -> "FireTurret"
            unitName.contains("Rocket") -> "FireRocketTruck"
            unitName.contains("Mortar") -> "FireMortarTruck"
            unitName.contains("Raptor") -> "FireRaptorFighter"
            unitName.contains("Corvette") -> "FireCorvetteFighter"
            unitName.contains("Battlecruiser") -> "FireBattlecruiser"
            unitName.contains("Intrepid") -> "FireIntrepid"
            unitName.contains("Condor") -> "FireCondorBomber"
            unitName.contains("Annihilator") -> "FireAnnihilatorTank"
            unitName.contains("Stealth") -> "FireStealthTank"
            unitName.contains("HeavyCommando") -> "FireHeavyCommando"
            unitName.contains("StrikeCommando") -> "FireStrikeCommando"
            unitName.contains("Hunter") -> "FireHunterSupport"
            unitName.contains("Uboat") -> "FireUboat"
            else -> "FireScorpionTank"
        }
        play(key)
    }

    /**
     * Plays a one-shot sound by event name. Silently returns if the sound is unknown or audio
     * is unavailable.
     */
    fun play(name: String) {
        if (!available) return
        val path = soundFiles[name] ?: return
        val audio = cache.getOrPut(name) {
            try {
                mixer?.loadAudio(path) ?: return
            } catch (t: Throwable) {
                return
            }
        }
        try {
            val track = mixer?.createTrack() ?: return
            if (!track.setAudio(audio)) return
            track.play()
        } catch (t: Throwable) {
            // Ignore transient playback errors; sound is best-effort.
        }
    }

    /**
     * Starts (or switches to) a looping background-music track. Only one soundtrack plays at a
     * time; starting a different one stops the previous. No-op if the requested track is already
     * the current one.
     */
    fun startMusic(name: String) {
        if (!available) return
        // Same track already playing — don't restart (avoids a click every turn).
        if (currentMusic == name) return
        val path = soundFiles[name] ?: return
        val audio = cache.getOrPut(name) {
            try { mixer?.loadAudio(path) ?: return } catch (t: Throwable) { return }
        }
        try {
            // Stop and release the previous music track.
            musicTrack?.let { old ->
                old.stop()
            }
            musicTrack = null
            val track = mixer?.createTrack() ?: return
            if (!track.setAudio(audio)) return
            track.gain = MUSIC_GAIN
            track.tag("music")
            if (!track.play()) return
            // The loop count only sticks while the track is playing: MIX_SetTrackLoops has no
            // effect on a stopped track (a later play() resets it), so set loops AFTER play().
            // Setting it before play() made the background music play exactly once and stop.
            track.loops = -1            // loop indefinitely
            musicTrack = track
            currentMusic = name
        } catch (t: Throwable) {
            // Best-effort: a music hiccup must not break the game.
        }
    }

    /** Releases all audio and the mixer. */
    fun close() {
        if (!available) return
        try {
            mixer?.stopAllTracks()
            for (audio in cache.values) audio.close()
            cache.clear()
            mixer?.close()
            SDLMixer.quit()
        } catch (t: Throwable) {
            // Best-effort shutdown.
        }
        available = false
    }
}
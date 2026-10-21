package cn.enaium.battalion.game.ui

import cn.enaium.battalion.game.GameManager
import cn.enaium.battalion.game.Trigger
import cn.enaium.battalion.game.TriggerManager
import cn.enaium.battalion.game.triggers.AttackStartTrigger
import cn.enaium.battalion.game.triggers.BuildUnitTrigger
import cn.enaium.battalion.game.triggers.CaptureTerrainTrigger
import cn.enaium.battalion.game.triggers.ExtractResourcesTrigger
import cn.enaium.battalion.game.triggers.KillUnitTrigger
import cn.enaium.battalion.game.triggers.MoveStartTrigger
import cn.enaium.battalion.game.triggers.PlayerTrigger
import cn.enaium.battalion.game.triggers.UpgradeUnitTrigger
import cn.enaium.battalion.util.Fio
import cn.enaium.sdl.SDLAudioDeviceID
import cn.enaium.sdl.mixer.SDLMixer

/**
 * Plays the game's sound effects through SDL_mixer.
 *
 * Every sound is an MP3 under the project's `assets/sounds/` directory, named by its event
 * name (`ExplosionSound.mp3`, `PlayerLoopWAV_3.mp3`). We scan that directory, index the files
 * by base name, and play the right one whenever the matching [Trigger] fires. Mods add a
 * sound by dropping `<event-name>.mp3` into the directory.
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

    /**
     * Latched when END_GAME fires. START_TURN events queued behind it must not switch the
     * faction music — the end-of-game dialogue plays over the last turn's music, and only
     * the results screen (GameMenu's playUI) changes it afterwards. gameOver is set later,
     * when EndGameAction finalises, so it cannot guard this window.
     */
    private var matchEnding = false

    /** The currently playing looping background-music track (one at a time). */
    private var musicTrack: cn.enaium.sdl.mixer.SDLTrack? = null

    /** The name of the soundtrack currently looping (to avoid restarts that don't change it). */
    private var currentMusic: String? = null

    /**
     * Master effect/music toggles (the SWF's `sound` / `music` options). When [effectsEnabled]
     * is false all one-shot play calls are suppressed; when [musicEnabled] is false the looping
     * soundtracks are stopped and not restarted.
     */
    var effectsEnabled: Boolean = true
    var musicEnabled: Boolean = true

    /** Looping background soundtracks: the menu, the player-side ("good") and enemy-side ("evil") loops. */
    private companion object {
        /** Main-menu BGM: the original MainMenu loops UIWAV as its "background" sound. */
        const val MENU_LOOP = "UIWAV"
        const val PLAYER_LOOP = "PlayerLoopWAV_3"
        const val ENEMY_LOOP = "AILoopWAV_3"
        const val MUSIC_GAIN = 0.5f
    }

    /**
     * Initialises SDL_mixer and indexes the sounds directory. Safe to call once before the
     * game starts; if SDL_mixer can't initialise (no audio device), the manager simply stays
     * silent. [subscribe] must be called separately — once per game, AFTER the [GameManager]
     * is constructed, because constructing a game clears the whole trigger event bus
     * (`TriggerManager.clearAll`), which would otherwise wipe the listeners registered here.
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
    }

    /**
     * Indexes every sound in the sounds directory by its event name, then overlays the mods'
     * sounds (a mod file with the same id replaces the game's; later mods win).
     */
    private fun indexSounds() {
        // Make sure the mod layers exist before the index is built (idempotent per assets dir).
        cn.enaium.battalion.mod.ModRegistry.ensureLoaded(assetsDir)
        val dir = "$assetsDir/sounds"
        for (file in Fio.listDir(dir).orEmpty()) {
            if (!file.endsWith(".mp3")) continue
            // Assets are stored under their event name (e.g. `ExplosionSound.mp3`,
            // `PlayerLoopWAV_3.mp3`), so the key is simply the file base name.
            val name = file.substringBeforeLast('.')
            if (name.isNotEmpty()) soundFiles[name] = "$dir/$file"
        }
        for (mod in cn.enaium.battalion.mod.ModRegistry.mods) {
            for ((id, path) in mod.sounds) soundFiles[id] = path
        }
    }

    /** Re-indexes the sounds after the mod set changed (switching a mod off/on). */
    fun reloadIndex() {
        soundFiles.clear()
        indexSounds()
    }

    /**
     * Subscribes the manager to the gameplay triggers that map to sounds.
     *
     * MUST be called once per match, AFTER the [GameManager] is constructed: `GameManager`'s
     * init clears every [TriggerManager] listener (`clearAll`), so listeners registered before
     * the game exists are dropped and music/sfx never play. Called from the game loop after
     * each mission load.
     */
    fun subscribe() {
        matchEnding = false
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
        // Movement sound fires when the walk STARTS (MoveStartTrigger), so the effect is
        // simultaneous with the first step instead of trailing the completed animation.
        TriggerManager.addListener(MoveStartTrigger.NAME) { t ->
            if (t is MoveStartTrigger) playMoveSound(t.unit.unitData.name)
        }
        // Weapon sound fires at the muzzle flash (attackStart), simultaneous with the firing
        // animation — the impact AttackUnitTrigger only drives the explosion visual.
        TriggerManager.addListener(AttackStartTrigger.NAME) { t ->
            if (t is AttackStartTrigger) playAttackSound(t.unit.unitData.name)
        }
        // END_GAME fires before gameOver is set, so latch it here: no more faction music
        // switches once the match has ended (the dialogue / results screen owns the music).
        TriggerManager.addListener(PlayerTrigger.END_GAME) { matchEnding = true }
        TriggerManager.addListener(PlayerTrigger.START_TURN) {
            // Start (or switch) the background music for whose turn it is, then play the
            // turn-start jingle. A queued START_TURN may fire after the match ends — the
            // end-of-game dialogue / results screen owns the music by then.
            if (matchEnding || GameManager.instance.gameOver) return@addListener
            val active = GameManager.instance.activeController
            val allied = active != null && GameManager.instance.isAlliedToThePlayer(active)
            startMusic(if (allied) PLAYER_LOOP else ENEMY_LOOP)
            play("StartTurn")
        }
    }

    /**
     * Plays the results-screen fanfare — the original GameOverMenu plays VictoryWAV on a win
     * (DefeatWAV on a loss). Called exactly when the results screen becomes visible, not when
     * the match ends (the end-of-game dialogue plays before it).
     */
    fun playEndGameMusic(playerWon: Boolean) {
        play(if (playerWon) "VictoryWAV" else "DefeatWAV")
    }

    /** Picks a movement sound for the given unit type name. */
    private fun playMoveSound(unitName: String) {
        // Naval units must be matched before the generic Fighter/Tank rules: "Corvette
        // Fighter" and "Leviathan Barge" would otherwise fall through to aircraft/tank
        // sounds. The original SWF dispatches by unit category: barges -> BoatMove,
        // submarines -> SubmarineMove, warships -> ShipMove (the exported U-Boat is
        // hyphenated, so a plain "Uboat" test misses it).
        val key = when {
            unitName.contains("Tank") -> "TankMove1"
            unitName.contains("Blimp") -> "BlimpMove"
            unitName.contains("Albatross") -> "AlbatrossMove"
            unitName.contains("Barge") || unitName.contains("Boat") -> "BoatMove"
            unitName.contains("Submarine") || unitName.contains("U-Boat") -> "SubmarineMove"
            unitName.contains("Ship") || unitName.contains("Battlecruiser") ||
                unitName.contains("Intrepid") || unitName.contains("Corvette") -> "ShipMove"
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
        if (!available || !effectsEnabled) return
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

    /** Starts the main-menu BGM loop. No-op once it is already playing. */
    fun startMenuMusic() = startMusic(MENU_LOOP)

    /** Restarts the loop for whose turn it is (used when the music option is re-enabled). */
    fun startTurnMusic() {
        val active = GameManager.instance.activeController
        val allied = active != null && GameManager.instance.isAlliedToThePlayer(active)
        startMusic(if (allied) PLAYER_LOOP else ENEMY_LOOP)
    }

    /** Stops any looping background music (used when the music option is turned off). */
    fun stopMusic() {
        if (!available) return
        musicTrack?.let { old ->
            old.stop()
            musicTrack = null
            currentMusic = null
        }
    }

    /**
     * Starts (or switches to) a looping background-music track. Only one soundtrack plays at a
     * time; starting a different one stops the previous. No-op if the requested track is already
     * the current one.
     */
    fun startMusic(name: String) {
        if (!available) return
        // Music toggled off: stop any loop and don't start another (the SWF's master loop
        // volume, but implemented as a hard stop so nothing plays in the background).
        if (!musicEnabled) {
            if (musicTrack != null) {
                musicTrack?.stop()
                musicTrack = null
                currentMusic = null
            }
            return
        }
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
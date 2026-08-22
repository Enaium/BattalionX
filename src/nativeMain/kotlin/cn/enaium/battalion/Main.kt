package cn.enaium.battalion

import cn.enaium.battalion.game.BattalionGame

/**
 * Native (macOS / Linux) entry point. Same arguments as the JVM entry point; FileKit does not
 * need explicit initialisation on native.
 */
fun main(args: Array<String>) {
    val dir = args.firstOrNull { !it.startsWith("--") } ?: "assets"
    val missionArg = args.indexOf("--mission")
    val mission = if (missionArg >= 0 && missionArg + 1 < args.size) args[missionArg + 1].toIntOrNull() ?: 0 else 0
    val skirmishArg = args.indexOf("--skirmish")
    val skirmish = if (skirmishArg >= 0 && skirmishArg + 1 < args.size) args[skirmishArg + 1] else null

    // Show the start menu by default; only a real --mission/--skirmish flag pins a specific
    // mission (passing a non-null default would skip the menu and jump into mission 0).
    val playMission = if (missionArg >= 0) mission else null
    BattalionGame(dir, playMission, skirmish).run()
}
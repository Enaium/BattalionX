package cn.enaium.battalion.game

import cn.enaium.battalion.game.responses.ChatResponse
import cn.enaium.battalion.game.responses.HighlightResponse

/**
 * Drives scripted [Response]s one at a time, in order.
 *
 * When a [GameEvent] becomes satisfied it is queued here. Each frame the current event's next
 * un-completed response is fired (or updated if already firing). Once every response in an event
 * completes, the event is reset (allowing it to repeat) and the next queued event is processed.
 *
 * Ported from the original `com.gamedozer.battalion.game.ResponseManager`.
 */
object ResponseManager {
    private val events = mutableListOf<GameEvent>()

    fun add(gameEvent: GameEvent) {
        events.add(gameEvent)
    }

    fun isEmpty(): Boolean = events.isEmpty()

    /** Drives the front-most event's responses. */
    fun update() {
        if (events.isEmpty()) return
        val currentEvent = events[0]
        val response = currentEvent.nextResponse() ?: run {
            // All responses done -> reset the event (repeat) and move on.
            currentEvent.reset()
            events.removeAt(0)
            return
        }
        try {
            if (!response.isFired()) response.fire() else response.update()
        } catch (e: Throwable) {
            // A single bad response must not stall the rest of the script.
        }
    }

    /**
     * The response that would run next after the (optional) start, searching across all queued
     * events, or null. Used by chat/highlight sequencing.
     */
    fun peek(startResponse: Response? = null): Response? {
        var started = startResponse == null
        for (event in events) {
            for (r in event.getResponses()) {
                if (started) {
                    if (!r.isCompleted()) return r
                } else if (r === startResponse) {
                    started = true
                }
            }
        }
        return null
    }

    /**
     * True if, after [startResponse], the next un-completed sequence contains a [ChatResponse]
     * before any other (non-highlight) response. Used to decide whether a chat window should
     * stay up across a run of highlights.
     */
    fun hasChatAfterAnUninterruptedSetOfHighlights(startResponse: Response): Boolean {
        var started = false
        for (event in events) {
            for (r in event.getResponses()) {
                if (started) {
                    if (!r.isCompleted()) {
                        if (r !is HighlightResponse) {
                            return r is ChatResponse
                        }
                    }
                } else if (r === startResponse) {
                    started = true
                }
            }
        }
        return true
    }

    /** Clears all queued events (used on game reset / end). */
    fun clear() {
        events.clear()
    }
}

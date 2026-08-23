package cn.enaium.battalion.game

import cn.enaium.battalion.data.ResponseConfig
import cn.enaium.battalion.data.TriggerConfig
import cn.enaium.battalion.game.responses.*
import cn.enaium.battalion.data.parseArgb

/**
 * Builds a [Response] from a typed [ResponseConfig] inside a map script.
 *
 * Mirrors the original `com.gamedozer.battalion.game.ResponseFactory.get`. Values come from
 * the config's typed fields; player indices are 1-indexed (decremented on parse where the
 * original did so).
 */
object ResponseFactory {
    fun get(cfg: ResponseConfig): Response {
        return when (cfg.type) {
            "incrementCounterVariable" -> IncrementCounterVariableResponse(cfg.counter)
            "decrementCounterVariable" -> DecrementCounterVariableResponse(cfg.counter)
            "setBooleanVariable" -> SetBooleanVariableResponse(
                cfg.boolean,
                cfg.value.lowercase().startsWith("t"),
            )
            "chat" -> ChatResponse(
                cfg.sender,
                cfg.message,
                cfg.avatar,
                parseArgb(cfg.color),
            )
            "highlight" -> HighlightResponse(
                cfg.x ?: 0,
                cfg.y ?: 0,
                cfg.show,
                cfg.updateCamera,
            )
            "disableControllers" -> DisableControllersResponse(cfg.disable)
            "disableMorale" -> DisableMoraleResponse(cfg.disable)
            "trace" -> TraceResponse(cfg.message)
            "sendAction" -> SendActionResponse(ActionFactory.get(cfg))
            "sendTrigger" -> SendTriggerResponse(TriggerFactory.get(cfg.trigger ?: TriggerConfig("")))
            "nameUnit" -> NameUnitResponse(
                cfg.x ?: 0,
                cfg.y ?: 0,
                cfg.value.ifEmpty { cfg.message }.ifEmpty { cfg.unit },
            )
            "createTransportedUnit" -> CreateTransportedUnitResponse(
                cfg.x ?: 0,
                cfg.y ?: 0,
                cfg.transportType,
                cfg.transporterType,
                cfg.player ?: 1,
            )
            "createUnit" -> CreateUnitResponse(
                cfg.x ?: 0,
                cfg.y ?: 0,
                cfg.type_,
                cfg.resources != null,
                cfg.resources ?: 2000,
                cfg.player ?: 1,
                cfg.fadeIn,
                cfg.parallel,
            )
            "destroyUnit" -> DestroyUnitResponse(cfg.x ?: 0, cfg.y ?: 0)
            "removeUnit" -> RemoveUnitResponse(
                cfg.x ?: 0,
                cfg.y ?: 0,
                cfg.fadeOut,
            )
            "scroll" -> ScrollResponse(cfg.x ?: 0, cfg.y ?: 0)
            "damageUnit" -> DamageUnitResponse(
                cfg.x ?: 0,
                cfg.y ?: 0,
                cfg.amount ?: 0.0,
            )
            "changeAlliance" -> ChangeAllianceResponse(
                cfg.playerA ?: 1,
                cfg.playerB ?: 1,
                cfg.allied,
            )
            "unitDisable", "disableUnit" -> UnitDisableResponse(
                cfg.x ?: 0,
                cfg.y ?: 0,
                cfg.disable,
            )
            "surrenderPlayer" -> SurrenderPlayerResponse(cfg.surrenderPlayer ?: cfg.player ?: 1)
            "structureResources" -> StructureResourcesResponse(
                cfg.x ?: 0,
                cfg.y ?: 0,
                cfg.amount?.toInt() ?: 0,
            )
            "resources" -> ResourcesResponse(
                cfg.player ?: 0,
                cfg.amount?.toInt() ?: 0,
            )
            "disableAllUnits" -> DisableAllUnitsResponse(
                cfg.disable,
                cfg.player ?: 0,
            )
            "enableUnit" -> EnableUnitResponse(
                cfg.enable,
                cfg.player ?: 0,
                cfg.unit,
            )
            "customScore" -> CustomScoreResponse(
                cfg.player ?: -1,
                cfg.variable,
                cfg.points ?: 0,
            )
            else -> Response()
        }
    }
}

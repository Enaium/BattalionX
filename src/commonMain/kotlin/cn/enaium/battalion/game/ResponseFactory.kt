package cn.enaium.battalion.game

import cn.enaium.battalion.game.responses.*
import cn.enaium.battalion.util.XmlNode

/**
 * Builds a [Response] from a `<response>` XML node inside a map `<script>`.
 *
 * Mirrors the original `com.gamedozer.battalion.game.ResponseFactory.get`. Values are read as
 * `value` attributes of the node's children; player indices are 1-indexed (decremented on parse
 * where the original did so).
 */
object ResponseFactory {
    fun get(node: XmlNode): Response {
        val type = node.attr("type")
        return when (type) {
            "incrementCounterVariable" -> IncrementCounterVariableResponse(node.child("counter")?.value() ?: "")
            "decrementCounterVariable" -> DecrementCounterVariableResponse(node.child("counter")?.value() ?: "")
            "setBooleanVariable" -> SetBooleanVariableResponse(
                node.child("boolean")?.value() ?: "",
                node.child("value")?.value()?.lowercase()?.startsWith("t") == true,
            )
            "chat" -> ChatResponse(
                node.child("sender")?.value() ?: "",
                node.child("message")?.value() ?: "",
                node.child("avatar")?.value() ?: "",
                node.child("color")?.value()?.toLong(16)?.toInt() ?: 0xFF00FF00.toInt(),
            )
            "highlight" -> HighlightResponse(
                node.child("x")?.value()?.toIntOrNull() ?: 0,
                node.child("y")?.value()?.toIntOrNull() ?: 0,
                node.child("show")?.value()?.lowercase()?.startsWith("t") == true,
                node.child("updateCamera")?.value()?.lowercase()?.startsWith("t") ?: true,
            )
            "disableControllers" -> DisableControllersResponse(
                node.child("disable")?.value()?.lowercase()?.startsWith("f") != true,
            )
            "disableMorale" -> DisableMoraleResponse(
                node.child("disable")?.value()?.lowercase()?.startsWith("f") != true,
            )
            "trace" -> TraceResponse(node.child("message")?.value() ?: "")
            "sendAction" -> SendActionResponse(
                ActionFactory.get(node.child("SurrenderAction") ?: XmlNode("SurrenderAction", emptyMap(), emptyList(), ""))
            )
            "sendTrigger" -> SendTriggerResponse(TriggerFactory.get(node.child("trigger") ?: XmlNode("trigger", emptyMap(), emptyList(), "")))
            "nameUnit" -> NameUnitResponse(
                node.child("x")?.value()?.toIntOrNull() ?: 0,
                node.child("y")?.value()?.toIntOrNull() ?: 0,
                node.child("name")?.value() ?: "",
            )
            "createTransportedUnit" -> CreateTransportedUnitResponse(
                node.child("x")?.value()?.toIntOrNull() ?: 0,
                node.child("y")?.value()?.toIntOrNull() ?: 0,
                node.child("transportType")?.value() ?: "",
                node.child("transporterType")?.value() ?: "",
                node.child("player")?.value()?.toIntOrNull() ?: 1,
            )
            "createUnit" -> CreateUnitResponse(
                node.child("x")?.value()?.toIntOrNull() ?: 0,
                node.child("y")?.value()?.toIntOrNull() ?: 0,
                node.child("type")?.value() ?: "",
                node.child("resources") != null,
                node.child("resources")?.value()?.toIntOrNull() ?: 2000,
                node.child("player")?.value()?.toIntOrNull() ?: 1,
                node.child("fadeIn")?.value()?.lowercase() == "true",
                node.child("parallel")?.value()?.lowercase() == "true",
            )
            "destroyUnit" -> DestroyUnitResponse(
                node.child("x")?.value()?.toIntOrNull() ?: 0,
                node.child("y")?.value()?.toIntOrNull() ?: 0,
            )
            "removeUnit" -> RemoveUnitResponse(
                node.child("x")?.value()?.toIntOrNull() ?: 0,
                node.child("y")?.value()?.toIntOrNull() ?: 0,
                node.child("fadeOut")?.value()?.lowercase().let { it == "true" } == true,
            )
            "scroll" -> ScrollResponse(
                node.child("x")?.value()?.toIntOrNull() ?: 0,
                node.child("y")?.value()?.toIntOrNull() ?: 0,
            )
            "damageUnit" -> DamageUnitResponse(
                node.child("x")?.value()?.toIntOrNull() ?: 0,
                node.child("y")?.value()?.toIntOrNull() ?: 0,
                node.child("amount")?.value()?.toDoubleOrNull() ?: 0.0,
            )
            "changeAlliance" -> ChangeAllianceResponse(
                node.child("playerA")?.value()?.toIntOrNull() ?: 1,
                node.child("playerB")?.value()?.toIntOrNull() ?: 1,
                node.child("allied")?.value()?.lowercase()?.startsWith("t") == true ||
                    node.child("allied")?.value() == "1",
            )
            "unitDisable" -> UnitDisableResponse(
                node.child("x")?.value()?.toIntOrNull() ?: 0,
                node.child("y")?.value()?.toIntOrNull() ?: 0,
                node.child("disable")?.value()?.lowercase()?.startsWith("t") == true,
            )
            "surrenderPlayer" -> SurrenderPlayerResponse(node.child("player")?.value()?.toIntOrNull() ?: 1)
            "structureResources" -> StructureResourcesResponse(
                node.child("x")?.value()?.toIntOrNull() ?: 0,
                node.child("y")?.value()?.toIntOrNull() ?: 0,
                node.child("amount")?.value()?.toIntOrNull() ?: 0,
            )
            "resources" -> ResourcesResponse(
                node.child("player")?.value()?.toIntOrNull() ?: 0,
                node.child("amount")?.value()?.toIntOrNull() ?: 0,
            )
            "disableAllUnits" -> DisableAllUnitsResponse(
                node.child("disable")?.value()?.lowercase()?.startsWith("t") == true,
                node.child("player")?.value()?.toIntOrNull() ?: 0,
            )
            "enableUnit" -> EnableUnitResponse(
                node.child("enable")?.value()?.lowercase()?.startsWith("t") == true,
                node.child("player")?.value()?.toIntOrNull() ?: 0,
                node.child("unit")?.value() ?: "",
            )
            "customScore" -> CustomScoreResponse(
                node.child("player")?.value()?.toIntOrNull() ?: -1,
                node.child("variable")?.value() ?: "",
                node.child("points")?.value()?.toIntOrNull() ?: 0,
            )
            else -> Response()
        }
    }
}

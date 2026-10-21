package cn.enaium.battalion.mod

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A mod's `manifest.json`.
 *
 * [id] is the stable program-facing identity (namespaced, e.g. `enaium.crystal`) while [name] is
 * the human-facing label: renaming the mod never breaks saves, dependencies or other mods that
 * reference it. Everything is optional except [id] so a minimal mod is one field.
 */
@Serializable
data class ModManifest(
    @SerialName("id") val id: String,
    val version: String = "1.0.0",
    val name: String = "",
    val description: String = "",
    val authors: List<ModAuthor> = emptyList(),
    val license: String = "",
    val source: String = "",
    /** Icon resource id, resolved through [ResourceManager] (e.g. `ui/icon`). */
    val icon: String = "",
    /** Game version window this mod was written for. */
    val game: ModGameCompatibility? = null,
    val dependencies: List<ModDependency> = emptyList(),
    /** Load ordering hints; the loader also honours `dependencies`. */
    val loadAfter: List<String> = emptyList(),
    val loadBefore: List<String> = emptyList(),
) {
    /** The label shown in menus: [name] when present, else the id. */
    val displayName: String get() = name.ifEmpty { id }
}

@Serializable
data class ModAuthor(
    val name: String,
    val url: String = "",
)

@Serializable
data class ModDependency(
    val id: String,
    /** Version requirement, e.g. `>=2.0.0`; empty accepts any version. */
    val version: String = "",
) {
    /** Whether [actual] (a mod's version) satisfies this requirement. */
    fun satisfiedBy(actual: String): Boolean = VersionRequirement(version).matches(actual)
}

@Serializable
data class ModGameCompatibility(
    /** Minimum game version, e.g. `>=1.2.0` or `1.2.0`. */
    val minVersion: String = "",
    /** Maximum game version, e.g. `1.x` (prefix match) or `<=2.0.0`. */
    val maxVersion: String = "",
)

/**
 * A tiny version matcher for mod manifests: supports the operators `>=`, `>`, `<=`, `<`, `=`
 * and the wildcard `1.x` / `1.2.x`, which is all a manifest needs. Anything unparsable matches
 * (a malformed requirement must not lock a player out of a mod).
 */
class VersionRequirement(private val requirement: String) {

    fun matches(version: String): Boolean {
        val text = requirement.trim()
        if (text.isEmpty()) return true
        val operator = listOf(">=", "<=", "==", ">", "<", "=").firstOrNull { text.startsWith(it) }
        val target = (operator?.let { text.removePrefix(it) } ?: text).trim()
        if (target.isEmpty() || target == "*") return true

        // Wildcards: "1.x" / "1.2.x" match by prefix.
        if (target.endsWith("x", ignoreCase = true)) {
            val prefix = target.substringBeforeLast('.').trimEnd('.')
            return version == prefix || version.startsWith("$prefix.")
        }
        val cmp = compare(version, target) ?: return true
        return when (operator ?: "=") {
            ">=" -> cmp >= 0
            ">" -> cmp > 0
            "<=" -> cmp <= 0
            "<" -> cmp < 0
            else -> cmp == 0 || version.startsWith("$target-")
        }
    }

    /** Numeric segment comparison; null when either side is not a dotted version. */
    private fun compare(a: String, b: String): Int? {
        val left = a.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
        val right = b.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
        if (left.isEmpty() || right.isEmpty()) return null
        for (i in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrElse(i) { 0 }
            val r = right.getOrElse(i) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }
}

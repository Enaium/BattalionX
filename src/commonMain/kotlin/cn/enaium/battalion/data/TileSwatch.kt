package cn.enaium.battalion.data

/**
 * A palette entry from a map document. It maps a *name* (a terrain or unit name, player
 * prefixes already stripped by the loader) to the sprite set that draws it, plus the owning
 * team.
 */
class TileSwatch(
    rawName: String,
    val team: Int = 0,
    sprites: TerrainSprites? = null,
    legacyImageCode: String = "",
) {
    val name: String
    val imageFiles: List<String>

    /** How this swatch's images assemble onto its tile (see [TerrainSprites.kind]). */
    val kind: TerrainSprites.Kind

    init {
        var n = rawName
        // Map file uses abbreviated names for some units.
        if (n == "Hunter") n = "Hunter Support"
        if (n == "Corvette") n = "Corvette Fighter"
        // "P5" entries are neutral in disguise.
        if (n.startsWith("P5")) n = "Neutral" + n.substring(2)
        name = n

        val s = sprites ?: parseLegacy(legacyImageCode)
        kind = s.kind
        imageFiles = when (kind) {
            TerrainSprites.Kind.COMPOSITE -> s.composite
            TerrainSprites.Kind.VARIANT -> s.variants
            TerrainSprites.Kind.SINGLE -> if (s.single.isNotEmpty()) listOf(s.single) else emptyList()
        }
    }

    /** Convenience constructor from a legacy image-code string (map editor exports). */
    constructor(imageCode: String) : this("", 0, null, imageCode)


    /** The owning team index (1..4), or 0 if the swatch is not team-owned. */
    fun ownerTeam(): Int = team

    override fun toString(): String = "<$name>"

    companion object {
        /**
         * Parses the original map editor's string mini-language into a [TerrainSprites]:
         * `"?a;b"` variants, `"+"` composites, `"@(team;file)"` team-owned singles.
         */
        fun parseLegacy(code: String): TerrainSprites {
            if (code.isEmpty()) return TerrainSprites()
            return when (code[0]) {
                '?' -> TerrainSprites(
                    variants = if (code.length >= 3)
                        code.substring(2, code.length - 1).split(";").map { it.substringBeforeLast('.') }
                    else emptyList())
                '+' -> TerrainSprites(
                    composite = if (code.length >= 3)
                        code.substring(2, code.length - 1).split(";").map { it.substringBeforeLast('.') }
                    else emptyList())
                '@', '%' -> {
                    val open = code.indexOf('(')
                    val close = code.indexOf(')')
                    if (open >= 0 && close > open)
                        TerrainSprites(single = code.substring(open + 1, close).substringBeforeLast('.'))
                    else TerrainSprites()
                }
                else -> TerrainSprites(single = code.substringBeforeLast('.'))
            }
        }
    }
}

package cn.enaium.battalion.data

/**
 * A palette entry from a map XML `<palette><entry ...>` element. It maps a *name* (a terrain
 * or unit name, with "P1 " player prefixes stripped) to an *image code* — a small string that
 * describes how to draw it and whether it is team-owned.
 *
 * Image code syntax:
 *  - `"file.png"`                  -> a single image
 *  - `"?a.png;b.png"`              -> randomly choose one of several images (terrain variants)
 *  - `"@(team;file.png)"` / `"%(team;...)"` -> owned by `team` (1..4), drawn with that team's
 *    colour; the team is read from the character at index 2 of the code
 *  - `"+a;b;c;d"`                  -> four images joined into one 2x2 tile
 *
 * For the logic port the swatch is used to look up terrain/unit names and ownership; the
 * image filenames are kept so the renderer can load the actual sprites.
 */
class TileSwatch(
    rawName: String,
    val imageCode: String,
) {
    val name: String
    val imageFiles: List<String>

    /**
     * How this swatch's image should be drawn onto its tile.
     *
     * The original `TileSwatch` GUI class distinguished three image-code prefixes
     * (`TileSwatch.as` → `generateTiles`):
     *  - `+`  : a *composite* tile — the four images are joined into a 2×2 quadrant tile
     *           (used for sea/shores/canyons where the four pieces describe the four edges).
     *  - `?`  : a *variant* tile — the tile shows ONE of several interchangeable textures
     *           (forest/plains/mountains/reef…). The original picks a random one at load time.
     *  - else : a single fixed texture.
     *
     * The KMP port MUST keep these apart: treating a `?` tile as a 2×2 composite (or vice
     * versa) renders every non-composite terrain as a broken checkerboard.
     */
    val kind: Kind

    init {
        var n = rawName
        var code = imageCode

        // Map file uses abbreviated names for some units.
        if (n == "Hunter") n = "Hunter Support"
        if (n == "Corvette") n = "Corvette Fighter"

        // "P5" entries are neutral in disguise: swap the team digit 5 for 0 in the image code.
        if (n.startsWith("P5")) {
            n = "Neutral" + n.substring(2)
            val imageNumberIndex = code.indexOf("5")
            if (imageNumberIndex >= 0) {
                code = code.substring(0, imageNumberIndex) + "0" + code.substring(imageNumberIndex)
            }
        }

        name = n
        kind = when (code.firstOrNull()) {
            '+' -> Kind.COMPOSITE
            '?' -> Kind.VARIANT
            else -> Kind.SINGLE
        }
        imageFiles = parseImageFiles(code)
    }

    /** The image-code prefix determines how the tile is drawn. */
    enum class Kind { COMPOSITE, VARIANT, SINGLE }

    /** The owning team index (1..4), or 0 if the swatch is not team-owned. */
    fun getTeam(): Int {
        val c = if (imageCode.isNotEmpty()) imageCode[0] else ' '
        if (c == '@' || c == '%') {
            if (imageCode.length >= 3) {
                val digit = imageCode[2]
                if (digit.isDigit()) return digit - '0'
            }
        }
        return 0
    }

    private fun parseImageFiles(code: String): List<String> {
        if (code.isEmpty()) return emptyList()
        return when (code[0]) {
            '?', '+' -> {
                if (code.length >= 3) code.substring(2, code.length - 1).split(";")
                else emptyList()
            }
            '@', '%' -> {
                val open = code.indexOf('(')
                val close = code.indexOf(')')
                if (open >= 0 && close > open) listOf(code.substring(open + 1, close)) else emptyList()
            }
            else -> listOf(code)
        }
    }

    override fun toString(): String = "<$name: $imageCode>"
}
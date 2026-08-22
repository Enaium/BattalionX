package cn.enaium.battalion.util

/**
 * A minimal, dependency-free XML parser.
 *
 * The Battalion Vengeance data files (units, terrain, modifiers, missions) are
 * simple, well-formed XML with elements, attributes, text and comments. Rather
 * than pulling in a full XML library, we parse just what these files need into
 * a tiny tree ([XmlNode]). This keeps the port self-contained and avoids any
 * dependency-resolution risk on the multiplatform target.
 *
 * Handles: XML declaration, comments, elements (open / close / self-closing),
 * attributes (single or double quoted), and text content. It is intentionally
 * lenient about whitespace.
 */
object XmlParser {

    /** Parses [xml] into a tree and returns the root element. */
    fun parse(xml: String): XmlNode {
        val p = Parser(xml)
        return p.parseDocument()
    }

    /** Parses [xml] and returns the root element, or null on failure. */
    fun parseOrNull(xml: String): XmlNode? = try {
        parse(xml)
    } catch (t: Throwable) {
        null
    }
}

/** A single XML element (node). */
class XmlNode(
    val name: String,
    val attributes: Map<String, String>,
    val children: List<XmlNode>,
    val text: String,
) {
    /** Returns an attribute value or [def] if missing. */
    fun attr(name: String, def: String = ""): String = attributes[name] ?: def

    /** Returns the first child element with [name], or null. */
    fun child(name: String): XmlNode? = children.firstOrNull { it.name == name }

    /** Returns all child elements with [name]. */
    fun children(name: String): List<XmlNode> = children.filter { it.name == name }

    /** Shortcut: reads the `value` attribute (used everywhere in these files). */
    fun value(): String = attributes["value"] ?: ""

    /** Serializes this node (and children) back to XML text, e.g. for a saved game. */
    fun toXmlString(): String {
        val sb = StringBuilder()
        sb.append('<').append(name)
        for ((k, v) in attributes) {
            sb.append(' ').append(k).append("=\"").append(escapeAttr(v)).append('"')
        }
        if (children.isEmpty() && text.isEmpty()) {
            sb.append("/>")
            return sb.toString()
        }
        sb.append('>')
        sb.append(escapeText(text))
        for (child in children) sb.append(child.toXmlString())
        sb.append("</").append(name).append('>')
        return sb.toString()
    }

    private fun escapeAttr(s: String): String =
        s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escapeText(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    override fun toString(): String = "<$name>"
}

private class Parser(private val s: String) {
    private var i = 0

    fun parseDocument(): XmlNode {
        skipPrologAndComments()
        val root = parseElement()
        return root
    }

    /** Skips the XML declaration, comments and whitespace. */
    private fun skipPrologAndComments() {
        while (true) {
            skipWhitespace()
            if (peek(2) == "<?") {
                skipTo("?>")
            } else if (peek(4) == "<!--") {
                skipTo("-->")
            } else {
                break
            }
        }
    }

    private fun skipWhitespace() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    /** Skips forward until [delim] is consumed (or end of input). */
    private fun skipTo(delim: String) {
        val idx = s.indexOf(delim, i)
        i = if (idx < 0) s.length else idx + delim.length
    }

    private fun peek(n: Int): String = if (i + n <= s.length) s.substring(i, i + n) else ""

    /** Parses one element starting at the current `'<'`. */
    private fun parseElement(): XmlNode {
        // expect '<'
        i++ // consume '<'
        val name = readName()
        val attrs = HashMap<String, String>()
        val children = ArrayList<XmlNode>()
        val text = StringBuilder()

        while (true) {
            skipWhitespace()
            if (i >= s.length) throw IllegalStateException("Unexpected end of XML inside <$name>")
            val c = s[i]
            when {
                c == '/' -> { // closing tag: </name> or self-closing />
                    if (peek(1) == ">") { // self-closing
                        i += 2
                        return XmlNode(name, attrs, children, text.toString())
                    }
                    // </name>
                    i++
                    skipWhitespace()
                    readName() // should match name; we ignore validation
                    skipWhitespace()
                    if (i < s.length && s[i] == '>') i++
                    return XmlNode(name, attrs, children, text.toString())
                }
                c == '>' -> {
                    i++
                    // parse children / text until closing tag
                    parseContent(name, children, text)
                    return XmlNode(name, attrs, children, text.toString())
                }
                else -> {
                    // attribute
                    val attrName = readName()
                    skipWhitespace()
                    if (i < s.length && s[i] == '=') {
                        i++
                        skipWhitespace()
                        val quote = s[i]
                        val end = s.indexOf(quote, i + 1)
                        val value = s.substring(i + 1, end)
                        i = end + 1
                        attrs[attrName] = value
                    } else {
                        attrs[attrName] = ""
                    }
                }
            }
        }
    }

    /** Reads text and child elements until the matching `</name>`. */
    private fun parseContent(name: String, children: ArrayList<XmlNode>, text: StringBuilder) {
        while (i < s.length) {
            if (peek(2) == "</") {
                // closing tag for this element
                val end = s.indexOf('>', i)
                i = end + 1
                return
            } else if (peek(4) == "<!--") {
                skipTo("-->")
            } else if (s[i] == '<') {
                children.add(parseElement())
            } else {
                // accumulate text
                val start = i
                while (i < s.length && s[i] != '<') i++
                text.append(s, start, i)
            }
        }
    }

    /** Reads an identifier (tag or attribute name). */
    private fun readName(): String {
        val start = i
        while (i < s.length && !s[i].isWhitespace() && s[i] != '>' && s[i] != '/' && s[i] != '=') i++
        return s.substring(start, i)
    }
}
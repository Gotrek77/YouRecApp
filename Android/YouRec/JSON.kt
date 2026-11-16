package yourec

import java.util.UUID

/// A small strict JSON reader/writer. Project documents use the same shapes as
/// the iPhone app's Codable output, so a project folder opens on either system.
/// Values are Map<String, Any?>, List<Any?>, String, Long, Double, Boolean, null.
object JSON {
    class Malformed(message: String) : Exception(message)

    fun parse(bytes: ByteArray): Any? = Reader(String(bytes, Charsets.UTF_8)).document()

    /// Sorted keys make equal values produce equal bytes: snapshots are named by hash.
    fun write(value: Any?): ByteArray = StringBuilder().also { put(it, value) }.toString().toByteArray(Charsets.UTF_8)

    private fun put(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value)
            is Int -> out.append(value)
            is Long -> out.append(value)
            is Double -> {
                if (!value.isFinite()) throw Malformed("A non-finite number cannot be saved")
                // Whole values stay valid for integer readers; the rest round-trips exactly.
                if (value == Math.rint(value) && Math.abs(value) < 9.0e15) out.append(value.toLong()) else out.append(value.toString())
            }
            is String -> quote(out, value)
            is List<*> -> { out.append('['); value.forEachIndexed { i, item -> if (i > 0) out.append(','); put(out, item) }; out.append(']') }
            is Map<*, *> -> {
                out.append('{')
                value.keys.map { it as String }.sorted().forEachIndexed { i, key ->
                    if (i > 0) out.append(','); quote(out, key); out.append(':'); put(out, value[key])
                }
                out.append('}')
            }
            else -> throw Malformed("Unsupported value " + value.javaClass.name)
        }
    }

    private fun quote(out: StringBuilder, text: String) {
        out.append('"')
        for (c in text) when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c < ' ' -> out.append("\\u").append(String.format("%04x", c.code))
            else -> out.append(c)
        }
        out.append('"')
    }

    private class Reader(val text: String) {
        var at = 0
        fun document(): Any? { val value = value(0); space(); if (at != text.length) fail("Unexpected trailing text"); return value }
        fun fail(message: String): Nothing = throw Malformed("$message at offset $at")
        fun space() { while (at < text.length && (text[at] == ' ' || text[at] == '\n' || text[at] == '\r' || text[at] == '\t')) at++ }
        fun members(depth: Int): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>(); space()
            if (at < text.length && text[at] == '}') { at++; return map }
            while (true) {
                space(); if (at >= text.length || text[at] != '"') fail("Expected a key")
                val key = string(); space()
                if (at >= text.length || text[at] != ':') fail("Expected ':'")
                at++; map[key] = value(depth + 1); space()
                if (at < text.length && text[at] == ',') { at++; continue }
                if (at < text.length && text[at] == '}') { at++; return map }
                fail("Expected ',' or '}'")
            }
        }
        fun items(depth: Int): List<Any?> {
            val list = ArrayList<Any?>(); space()
            if (at < text.length && text[at] == ']') { at++; return list }
            while (true) {
                list.add(value(depth + 1)); space()
                if (at < text.length && text[at] == ',') { at++; continue }
                if (at < text.length && text[at] == ']') { at++; return list }
                fail("Expected ',' or ']'")
            }
        }
        fun value(depth: Int): Any? {
            if (depth > 512) fail("Nesting is too deep")
            space()
            if (at >= text.length) fail("Unexpected end")
            return when (val c = text[at]) {
                '{' -> { at++; members(depth) }
                '[' -> { at++; items(depth) }
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else fail("Unexpected character")
            }
        }
        fun word(name: String, result: Any?): Any? { if (!text.startsWith(name, at)) fail("Unexpected word"); at += name.length; return result }
        fun number(): Any {
            val start = at
            if (text[at] == '-') at++
            while (at < text.length && text[at] in '0'..'9') at++
            var real = false
            if (at < text.length && text[at] == '.') { real = true; at++; while (at < text.length && text[at] in '0'..'9') at++ }
            if (at < text.length && (text[at] == 'e' || text[at] == 'E')) {
                real = true; at++
                if (at < text.length && (text[at] == '+' || text[at] == '-')) at++
                while (at < text.length && text[at] in '0'..'9') at++
            }
            val digits = text.substring(start, at)
            return (if (real) null else digits.toLongOrNull()) ?: digits.toDoubleOrNull() ?: fail("Invalid number")
        }
        fun string(): String {
            at++; val out = StringBuilder()
            while (true) {
                if (at >= text.length) fail("Unterminated string")
                val c = text[at++]
                if (c == '"') return out.toString()
                if (c != '\\') { out.append(c); continue }
                if (at >= text.length) fail("Unterminated escape")
                when (val e = text[at++]) {
                    '"', '\\', '/' -> out.append(e)
                    'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                    'b' -> out.append('\b'); 'f' -> out.append('\u000c')
                    'u' -> {
                        if (at + 4 > text.length) fail("Invalid escape")
                        out.append((text.substring(at, at + 4).toIntOrNull(16) ?: fail("Invalid escape")).toChar()); at += 4
                    }
                    else -> fail("Invalid escape")
                }
            }
        }
    }
}

/// Typed access to one decoded object. A wrong or missing value is an invalid
/// document, never a silently substituted default.
class Fields(private val map: Map<String, Any?>) {
    companion object {
        fun of(value: Any?): Fields {
            @Suppress("UNCHECKED_CAST")
            return Fields(value as? Map<String, Any?> ?: throw ProjectError.InvalidDocument())
        }
        fun double(value: Any?): Double = when (value) { is Long -> value.toDouble(); is Double -> value; else -> throw ProjectError.InvalidDocument() }
        fun uuid(value: Any?): UUID {
            val text = value as? String ?: throw ProjectError.InvalidDocument()
            // UUID.fromString accepts short groups such as "1-2-3-4-5".
            if (text.length != 36) throw ProjectError.InvalidDocument()
            return try { UUID.fromString(text) } catch (e: IllegalArgumentException) { throw ProjectError.InvalidDocument() }
        }
    }
    val keys: Set<String> get() = map.keys
    fun has(key: String) = map[key] != null
    fun raw(key: String): Any? = map[key]
    fun string(key: String): String = map[key] as? String ?: throw ProjectError.InvalidDocument()
    fun stringOrNull(key: String): String? = if (has(key)) string(key) else null
    fun double(key: String): Double = double(map[key])
    fun doubleOrNull(key: String): Double? = if (has(key)) double(key) else null
    fun long(key: String): Long = map[key] as? Long ?: throw ProjectError.InvalidDocument()
    fun int(key: String): Int { val v = long(key); if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) throw ProjectError.InvalidDocument(); return v.toInt() }
    fun intOrNull(key: String): Int? = if (has(key)) int(key) else null
    fun bool(key: String): Boolean = map[key] as? Boolean ?: throw ProjectError.InvalidDocument()
    fun boolOrNull(key: String): Boolean? = if (has(key)) bool(key) else null
    fun uuid(key: String): UUID = uuid(map[key])
    fun uuidOrNull(key: String): UUID? = if (has(key)) uuid(key) else null
    fun list(key: String): List<Any?> = map[key] as? List<Any?> ?: throw ProjectError.InvalidDocument()
    fun listOrNull(key: String): List<Any?>? = if (has(key)) list(key) else null
    fun fields(key: String): Fields = of(map[key])
    fun fieldsOrNull(key: String): Fields? = if (has(key)) fields(key) else null
}

/// Swift writes uppercase UUID strings, and orders by them.
val UUID.text: String get() = toString().uppercase()

/// Build an object, leaving absent optionals out as Codable does.
fun jsonObject(vararg pairs: Pair<String, Any?>): Map<String, Any?> {
    val map = LinkedHashMap<String, Any?>()
    for ((key, value) in pairs) if (value != null) map[key] = value
    return map
}

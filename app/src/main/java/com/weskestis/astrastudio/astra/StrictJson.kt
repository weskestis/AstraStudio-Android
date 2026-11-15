package com.weskestis.astrastudio.astra

import org.json.JSONArray
import org.json.JSONObject

/** Same strict grammar on Android and the JVM: no duplicate keys, coercion or trailing input. */
internal class StrictJson private constructor(private val text: String) {
    private var at = 0
    private fun whitespace() { while (at < text.length && text[at] in " \r\n\t") at++ }
    private fun take(c: Char): Boolean { whitespace(); if (at < text.length && text[at] == c) { at++; return true }; return false }
    private fun need(c: Char) { require(take(c)) { "Expected '$c' at JSON position $at." } }
    private fun string(): String {
        need('"'); val out = StringBuilder()
        while (at < text.length) {
            val c = text[at++]
            if (c == '"') return out.toString()
            require(c.code >= 32) { "Unescaped control character in JSON." }
            if (c != '\\') out.append(c) else {
                require(at < text.length) { "Incomplete JSON escape." }
                when (val e = text[at++]) {
                    '"', '\\', '/' -> out.append(e)
                    'b' -> out.append('\b'); 'f' -> out.append('\u000c'); 'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                    'u' -> { require(at + 4 <= text.length) { "Incomplete Unicode escape." }; val code = text.substring(at, at+4).toIntOrNull(16) ?: error("Invalid Unicode escape."); out.append(code.toChar()); at += 4 }
                    else -> error("Invalid JSON escape.")
                }
            }
            require(out.length <= 32768) { "JSON string is too long." }
        }
        error("Unterminated JSON string.")
    }
    private fun value(depth: Int): Any {
        require(depth <= 12) { "JSON nesting exceeds 12 levels." }; whitespace()
        require(at < text.length) { "Incomplete JSON." }
        return when (text[at]) {
            '{' -> {
                at++; val out = JSONObject(); val keys = HashSet<String>()
                if (!take('}')) do {
                    val key = string(); require(keys.add(key)) { "Duplicate JSON key: ${key.take(64)}." }; need(':'); out.put(key, value(depth+1))
                    if (take('}')) break
                    need(',')
                } while (true)
                out
            }
            '[' -> {
                at++; val out = JSONArray()
                if (!take(']')) do {
                    require(out.length() < 4096) { "JSON array is too large." }; out.put(value(depth+1))
                    if (take(']')) break
                    need(',')
                } while (true)
                out
            }
            '"' -> string()
            't' -> { literal("true"); true }; 'f' -> { literal("false"); false }; 'n' -> { literal("null"); JSONObject.NULL }
            else -> {
                val match = number.find(text, at)?.takeIf { it.range.first == at } ?: error("Invalid JSON value at position $at.")
                at += match.value.length
                val n = match.value.toDoubleOrNull(); require(n != null && n.isFinite()) { "JSON number must be finite." }; n
            }
        }
    }
    private fun literal(s: String) { require(text.startsWith(s, at)) { "Invalid JSON literal." }; at += s.length }
    companion object {
        private val number = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
        fun objectFrom(raw: String): JSONObject {
            require(raw.length <= 262144) { "Command JSON exceeds 256 KiB." }
            var s = raw.trim()
            if (s.startsWith("```json\n") && s.endsWith("```")) s = s.removePrefix("```json\n").removeSuffix("```").trim()
            val parser = StrictJson(s); val result = parser.value(0)
            parser.whitespace(); require(parser.at == s.length) { "Unexpected text after JSON." }
            return result as? JSONObject ?: error("Astra expects a JSON object.")
        }
    }
}

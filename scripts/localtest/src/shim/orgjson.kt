// Minimal functional org.json for unit-test classpath (shadows android.jar stubs).
// Semantics match Android: optString returns "" for any non-string, NULL is its
// own singleton, isNull() is true for missing keys.
package org.json

class JSONException(message: String) : RuntimeException(message)

class JSONArray {
    private val items = ArrayList<Any?>()
    constructor()
    constructor(json: String) { JsonParser.parseArray(json, this) }
    constructor(list: Collection<*>) { list.forEach { items.add(unwrap(it)) } }
    constructor(array: Array<*>) { array.forEach { items.add(unwrap(it)) } }

    fun length(): Int = items.size
    fun isNull(i: Int) = items[i].let { it == null || it == JSONObject.NULL }
    fun opt(i: Int): Any? = items.getOrNull(i)
    fun get(i: Int): Any? = items[i]
    fun getString(i: Int) = items[i] as? String ?: throw JSONException("no string at $i")
    fun getInt(i: Int) = (items[i] as? Number)?.toInt() ?: (items[i] as? String)?.toInt() ?: throw JSONException("not int at $i")
    fun getLong(i: Int) = (items[i] as? Number)?.toLong() ?: (items[i] as? String)?.toLong() ?: throw JSONException("not long at $i")
    fun getDouble(i: Int) = (items[i] as? Number)?.toDouble() ?: (items[i] as? String)?.toDouble() ?: throw JSONException("not double at $i")
    fun getBoolean(i: Int) = items[i] as? Boolean ?: throw JSONException("not boolean at $i")
    fun getJSONObject(i: Int) = items[i] as? JSONObject ?: throw JSONException("not object at $i")
    fun getJSONArray(i: Int) = items[i] as? JSONArray ?: throw JSONException("not array at $i")
    fun optString(i: Int): String = when (val v = items.getOrNull(i)) { is String -> v; else -> "" }
    fun optJSONObject(i: Int): JSONObject? = items.getOrNull(i) as? JSONObject
    fun optJSONArray(i: Int): JSONArray? = items.getOrNull(i) as? JSONArray
    fun optInt(i: Int, fallback: Int): Int = (items.getOrNull(i) as? Number)?.toInt() ?: fallback
    fun optLong(i: Int, fallback: Long): Long = (items.getOrNull(i) as? Number)?.toLong() ?: fallback
    fun put(v: Any?) = apply { items.add(if (v == null) null else unwrap(v)) }
    fun put(i: Int, v: Any?) = apply {
        while (items.size < i) items.add(null)
        if (i == items.size) items.add(if (v == null) null else unwrap(v)) else items[i] = if (v == null) null else unwrap(v)
    }
    override fun toString(): String = items.joinToString(prefix = "[", postfix = "]") { JsonParser.render(it) }

    companion object {
        fun unwrap(v: Any?): Any? = when (v) {
            is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, vv) -> if (k != null) o.put(k.toString(), vv) } }
            is Collection<*> -> JSONArray(v)
            is Array<*> -> JSONArray(v)
            null -> null
            else -> v
        }
    }
}

class JSONObject {
    private val map = LinkedHashMap<String, Any?>()
    constructor()
    constructor(json: String) { JsonParser.parseObject(json, this) }
    constructor(map: Map<*, *>) { map.forEach { (k, v) -> if (k != null) put(k.toString(), v) } }

    fun has(key: String) = map.containsKey(key)
    fun isNull(key: String) = if (!map.containsKey(key)) true else map[key].let { it == null || it == NULL }
    fun length() = map.size
    fun keys(): Iterator<String> = map.keys.iterator()
    fun names(): JSONArray? = JSONArray(map.keys.toList())
    fun get(key: String): Any? = map[key]
    fun opt(key: String): Any? = map[key]
    fun optString(key: String): String = map[key] as? String ?: ""
    fun optString(key: String, fallback: String): String = (map[key] as? String) ?: fallback
    fun optInt(key: String, fallback: Int): Int = (map[key] as? Number)?.toInt() ?: fallback
    fun optLong(key: String, fallback: Long): Long = (map[key] as? Number)?.toLong() ?: fallback
    fun optDouble(key: String, fallback: Double): Double = (map[key] as? Number)?.toDouble() ?: fallback
    fun optBoolean(key: String, fallback: Boolean): Boolean = (map[key] as? Boolean) ?: fallback
    fun optJSONObject(key: String): JSONObject? = map[key] as? JSONObject
    fun optJSONArray(key: String): JSONArray? = map[key] as? JSONArray
    fun getString(key: String): String = map[key] as? String ?: throw JSONException("no string at $key")
    fun getInt(key: String): Int = (map[key] as? Number)?.toInt() ?: (map[key] as? String)?.toInt() ?: throw JSONException("no int at $key")
    fun getLong(key: String): Long = (map[key] as? Number)?.toLong() ?: (map[key] as? String)?.toLong() ?: throw JSONException("no long at $key")
    fun getBoolean(key: String): Boolean = map[key] as? Boolean ?: throw JSONException("no bool at $key")
    fun getJSONObject(key: String): JSONObject = map[key] as? JSONObject ?: throw JSONException("no object at $key")
    fun getJSONArray(key: String): JSONArray = map[key] as? JSONArray ?: throw JSONException("no array at $key")
    fun put(key: String, v: Any?) = apply { map[key] = if (v == null) null else JSONArray.unwrap(v) }
    fun put(key: String, v: Boolean) = apply { map[key] = v }
    fun put(key: String, v: Int) = apply { map[key] = v }
    fun put(key: String, v: Long) = apply { map[key] = v }
    fun put(key: String, v: Double) = apply { map[key] = v }
    fun remove(key: String): Any? = map.remove(key)
    override fun toString(): String = map.entries.joinToString(prefix = "{", postfix = "}") {
        JsonParser.render(it.key) + ":" + JsonParser.render(it.value)
    }

    companion object {
        @JvmField val NULL: Any = object { override fun toString() = "null" }
        fun quote(s: String) = JsonParser.render(s)
    }
}

internal object JsonParser {
    fun parseObject(json: String, into: JSONObject) {
        val p = P(json)
        p.expect('{')
        while (true) {
            when (p.peek()) {
                '}' -> { p.i++; return }
                ',' -> p.i++
                else -> {
                    val key = p.string()
                    p.expect(':')
                    into.put(key, p.value())
                }
            }
        }
    }
    fun parseArray(json: String, into: JSONArray) {
        val p = P(json)
        p.expect('[')
        while (true) {
            when (p.peek()) {
                ']' -> { p.i++; return }
                ',' -> p.i++
                else -> into.put(p.value())
            }
        }
    }

    class P(val s: String) {
        var i = 0
        fun peek(): Char { skip(); return if (i < s.length) s[i] else ' ' }
        fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun expect(c: Char) {
            skip()
            if (i >= s.length || s[i] != c) throw JSONException("expected $c at $i in $s")
            i++
        }
        fun string(): String {
            skip()
            if (s[i] != '"') throw JSONException("not a string at $i in $s")
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i]
                when {
                    c == '"' -> { i++; return sb.toString() }
                    c == '\\' -> {
                        i++
                        sb.append(
                            when (val e = s[i++]) {
                                'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; 'b' -> '\b'; 'f' -> ''
                                'u' -> { val hex = s.substring(i, i + 4); i += 4; hex.toInt(16).toChar() }
                                else -> e
                            })
                    }
                    else -> { sb.append(c); i++ }
                }
            }
        }
        fun value(): Any? {
            skip()
            return when {
                i >= s.length -> null
                s[i] == '"' -> string()
                s[i] == '{' -> { val o = JSONObject(); parseObjectFrom(this, o); o }
                s[i] == '[' -> { val a = JSONArray(); parseArrayFrom(this, a); a }
                s.startsWith("true", i) -> { i += 4; true }
                s.startsWith("false", i) -> { i += 5; false }
                s.startsWith("null", i) -> { i += 4; null }
                else -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
                    val t = s.substring(start, i)
                    t.toLongOrNull() ?: t.toDoubleOrNull() ?: t.toIntOrNull() ?: throw JSONException("bad number $t")
                }
            }
        }
        private fun parseObjectFrom(p: P, o: JSONObject) {
            p.expect('{')
            while (true) {
                when (p.peek()) {
                    '}' -> { p.i++; return }
                    ',' -> p.i++
                    else -> { val key = p.string(); p.expect(':'); o.put(key, p.value()) }
                }
            }
        }
        private fun parseArrayFrom(p: P, a: JSONArray) {
            p.expect('[')
            while (true) {
                when (p.peek()) {
                    ']' -> { p.i++; return }
                    ',' -> p.i++
                    else -> a.put(p.value())
                }
            }
        }
    }

    fun render(v: Any?): String = when (v) {
        null -> "null"
        is String -> buildString {
            append('"')
            for (c in v) {
                when (c) {
                    '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n")
                    '\t' -> append("\\t"); '\r' -> append("\\r")
                    else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
                }
            }
            append('"')
        }
        is JSONObject, is JSONArray -> v.toString()
        is Boolean -> v.toString()
        is Int, is Long, is Double, is Float -> v.toString()
        else -> render(v.toString())
    }
}

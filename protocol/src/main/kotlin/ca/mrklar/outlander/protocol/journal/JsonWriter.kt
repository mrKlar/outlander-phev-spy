package ca.mrklar.outlander.protocol.journal

/** Fragment JSON déjà encodé (objet ou tableau produit par [JsonWriter]),
 *  à imbriquer tel quel dans un autre objet/tableau. */
@JvmInline
value class JsonRaw(val text: String) {
    override fun toString(): String = text
}

/** Émetteur JSON minimal et correct, sans dépendance externe.
 *
 *  Valeurs acceptées par [encode] : `null`, [String], [Boolean], [Int],
 *  [Long], [Double] fini (via `toString()`), [JsonRaw], [Iterable] de
 *  valeurs, et [Map] à clés [String] (ordre d'itération conservé).
 *  Les objets construits par [obj] conservent l'ordre des paires. */
object JsonWriter {

    /** Encode [value] en JSON. Lève [IllegalArgumentException] pour un
     *  type non supporté ou un [Double] non fini (NaN, infinis). */
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is JsonRaw -> value.text
        is String -> encodeString(value)
        is Boolean -> value.toString()
        is Int -> value.toString()
        is Long -> value.toString()
        is Double -> {
            require(value.isFinite()) { "Double non fini non encodable en JSON: $value" }
            value.toString()
        }
        is Iterable<*> -> value.joinToString(separator = ",", prefix = "[", postfix = "]") {
            encode(it)
        }
        is Map<*, *> -> value.entries.joinToString(separator = ",", prefix = "{", postfix = "}") {
            val cle = it.key as? String
                ?: throw IllegalArgumentException("clé JSON non chaîne: ${it.key}")
            "${encodeString(cle)}:${encode(it.value)}"
        }
        else -> throw IllegalArgumentException("type non supporté pour JSON: ${value.javaClass.name}")
    }

    /** Objet JSON ordonné depuis une liste de paires clé/valeur. */
    fun obj(fields: List<Pair<String, Any?>>): JsonRaw =
        JsonRaw(fields.joinToString(separator = ",", prefix = "{", postfix = "}") { (cle, v) ->
            "${encodeString(cle)}:${encode(v)}"
        })

    /** Objet JSON ordonné depuis des paires clé/valeur. */
    fun obj(vararg fields: Pair<String, Any?>): JsonRaw = obj(fields.toList())

    /** Tableau JSON depuis des valeurs encodables par [encode]. */
    fun arr(values: Iterable<Any?>): JsonRaw = JsonRaw(encode(values))

    /** Chaîne JSON entre guillemets, échappement complet : `\"`, `\\`,
     *  `\b`, `\f`, `\n`, `\r`, `\t`, et `\u00XX` pour tout autre
     *  caractère de contrôle < 0x20. */
    fun encodeString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) {
                    sb.append("\\u%04x".format(c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

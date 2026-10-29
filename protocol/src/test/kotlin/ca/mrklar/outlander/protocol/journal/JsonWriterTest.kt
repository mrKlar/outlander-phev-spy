package ca.mrklar.outlander.protocol.journal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonWriterTest {

    @Test
    fun echappementCompletDesChaines() {
        val entree = "\"\\\b\u000C\n\r\t\u001F\u0000Aé"
        assertEquals(
            "\"\\\"\\\\\\b\\f\\n\\r\\t\\u001f\\u0000Aé\"",
            JsonWriter.encodeString(entree),
        )
    }

    @Test
    fun objetOrdonneEtValeursPrimitives() {
        val json = JsonWriter.obj(
            "nom" to "x",
            "entier" to 42,
            "long" to 1234567890123L,
            "double" to 14.2,
            "bool" to true,
            "rien" to null,
        )
        assertEquals(
            """{"nom":"x","entier":42,"long":1234567890123,"double":14.2,"bool":true,"rien":null}""",
            json.text,
        )
    }

    @Test
    fun tableauEtImbrication() {
        val json = JsonWriter.obj(
            "items" to listOf("a", null, 3),
            "sous" to JsonWriter.obj("k" to JsonWriter.arr(listOf(1L, 2L))),
        )
        assertEquals(
            """{"items":["a",null,3],"sous":{"k":[1,2]}}""",
            json.text,
        )
    }

    @Test
    fun doubleNonFiniRejeté() {
        assertFailsWith<IllegalArgumentException> { JsonWriter.encode(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { JsonWriter.encode(Double.POSITIVE_INFINITY) }
    }

    @Test
    fun typeNonSupporteRejeté() {
        assertFailsWith<IllegalArgumentException> { JsonWriter.encode(Any()) }
    }
}

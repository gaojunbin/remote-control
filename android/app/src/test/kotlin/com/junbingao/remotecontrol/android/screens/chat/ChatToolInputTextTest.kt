package com.junbingao.remotecontrol.android.screens.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** A tool's input, printed as the iPhone's `JSONSerialization` prints it with `.prettyPrinted` and `.sortedKeys`. */
class ChatToolInputTextTest {
    private fun render(json: String) = ToolInputText.render(Json.parseToJsonElement(json))

    @Test
    fun aStringIsPrintedAsItIs() {
        assertEquals("ls -la", ToolInputText.render(JsonPrimitive("ls -la")))
    }

    @Test
    fun anObjectIsPrintedSortedWithFoundationsSpacingAndEscapes() {
        assertEquals("{\n  \"command\" : \"cat a\\/b\",\n  \"timeout\" : 30\n}", render("""{"timeout":30,"command":"cat a/b"}"""))
        assertEquals(
            "{\n  \"paths\" : [\n    \"one\",\n    \"two\"\n  ],\n  \"recursive\" : true\n}",
            render("""{"recursive":true,"paths":["one","two"]}"""),
        )
    }

    @Test
    fun numbersAreFoundationsToo() {
        assertEquals("{\n  \"ratio\" : 1.5,\n  \"whole\" : 2\n}", render("""{"whole":2.0,"ratio":1.5}"""))
        assertEquals("a bare number is the encoder's own bytes", "42", render("42"))
    }
}

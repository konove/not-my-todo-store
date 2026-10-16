package io.github.konove.notmytodo.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class McpServerTest {
    private val echo = Tool(
        "echo", "Says it back",
        listOf(Param("text", ParamType.STRING, "What to say", required = true), Param("times", ParamType.INTEGER, "How often")),
    ) { args -> args.required("text").repeat(args.int("times") ?: 1) }
    private val broken = Tool("broken", "Fails", emptyList()) { throw ToolError("it broke") }
    private val server = McpServer("test", "1", { "Use echo." }, listOf(echo, broken))

    private fun call(line: String): JsonObject = JsonParser.parseString(server.handle(line)!!).asJsonObject

    @Test
    fun `initialize answers with the client's protocol version, the tools capability and the instructions`() {
        val result = call("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}""").getAsJsonObject("result")
        assertEquals("2025-03-26", result.get("protocolVersion").asString)
        assertTrue(result.getAsJsonObject("capabilities").has("tools"))
        assertEquals("test", result.getAsJsonObject("serverInfo").get("name").asString)
        assertEquals("Use echo.", result.get("instructions").asString)
        val bare = call("""{"jsonrpc":"2.0","id":2,"method":"initialize"}""").getAsJsonObject("result")
        assertEquals("2025-06-18", bare.get("protocolVersion").asString)
    }

    @Test
    fun `a request that fails is answered with an internal error, and the server goes on`() {
        val failing = McpServer("t", "1", { throw IllegalStateException("no store") }, listOf(echo))
        val answer = JsonParser.parseString(failing.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""")!!).asJsonObject
        assertEquals(-32603, answer.getAsJsonObject("error").get("code").asInt)
        val input = """{"jsonrpc":"2.0","id":1,"method":"initialize"}""" + "\n" + """{"jsonrpc":"2.0","id":2,"method":"ping"}""" + "\n"
        val output = ByteArrayOutputStream()
        failing.serve(ByteArrayInputStream(input.toByteArray()), output)
        assertEquals("""{"jsonrpc":"2.0","id":2,"result":{}}""", output.toString(Charsets.UTF_8).trim().lines().last())
    }

    @Test
    fun `tools are listed with their schemas`() {
        val tools = call("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").getAsJsonObject("result").getAsJsonArray("tools")
        val first = tools[0].asJsonObject
        assertEquals("echo", first.get("name").asString)
        val schema = first.getAsJsonObject("inputSchema")
        assertEquals("object", schema.get("type").asString)
        assertEquals("integer", schema.getAsJsonObject("properties").getAsJsonObject("times").get("type").asString)
        assertEquals("text", schema.getAsJsonArray("required")[0].asString)
    }

    @Test
    fun `a call answers with text, and a tool error with isError`() {
        val ok = call("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hi","times":2}}}""")
            .getAsJsonObject("result")
        assertEquals("hihi", ok.getAsJsonArray("content")[0].asJsonObject.get("text").asString)
        assertFalse(ok.get("isError").asBoolean)
        val bad = call("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"broken","arguments":{}}}""").getAsJsonObject("result")
        assertTrue(bad.get("isError").asBoolean)
        assertEquals("it broke", bad.getAsJsonArray("content")[0].asJsonObject.get("text").asString)
        val unknown = call("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"nope"}}""").getAsJsonObject("result")
        assertTrue(unknown.get("isError").asBoolean)
    }

    @Test
    fun `arguments are read leniently and unknown ones are named`() {
        val lenient = call("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"echo","arguments":{"text":"a","times":"3"}}}""")
        assertEquals("aaa", lenient.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString)
        val unknown = call("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"text":"a","colour":"red"}}}""")
            .getAsJsonObject("result")
        assertTrue(unknown.get("isError").asBoolean)
        assertTrue(unknown.getAsJsonArray("content")[0].asJsonObject.get("text").asString.contains("colour"))
        val missing = call("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{}}}""").getAsJsonObject("result")
        assertTrue(missing.getAsJsonArray("content")[0].asJsonObject.get("text").asString.contains("text"))
    }

    @Test
    fun `notifications get no answer, unknown methods an error, and serve reads line by line`() {
        assertNull(server.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertEquals(-32601, call("""{"jsonrpc":"2.0","id":9,"method":"resources/list"}""").getAsJsonObject("error").get("code").asInt)
        assertEquals(-32700, call("not json").getAsJsonObject("error").get("code").asInt)
        val input = """{"jsonrpc":"2.0","id":1,"method":"ping"}""" + "\n\n" + """{"jsonrpc":"2.0","method":"notifications/initialized"}""" + "\n"
        val output = ByteArrayOutputStream()
        server.serve(ByteArrayInputStream(input.toByteArray()), output)
        assertEquals("""{"jsonrpc":"2.0","id":1,"result":{}}""", output.toString(Charsets.UTF_8).trim())
    }
}

package io.github.konove.notmytodo.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.konove.notmytodo.store.Store
import io.github.konove.notmytodo.store.TempRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ServerTest {
    private val tmp = Files.createTempDirectory("nmt-server").toRealPath()
    private val repo = TempRepo.create(tmp.resolve("shop"), "git@github.com:me/shop.git")
    private val server = server(Workspace(Store(tmp.resolve("store"), home = tmp.resolve("home")), repo))

    private fun call(id: Int, method: String, params: String = "{}"): JsonObject =
        JsonParser.parseString(server.handle("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")!!)
            .asJsonObject.getAsJsonObject("result")

    private fun tool(id: Int, name: String, arguments: String): String =
        call(id, "tools/call", """{"name":"$name","arguments":$arguments}""").getAsJsonArray("content")[0].asJsonObject.get("text").asString

    @Test
    fun `the instructions name the folder, and the eight tools are listed with project`() {
        assertTrue(call(1, "initialize").get("instructions").asString.contains("shop"))
        val tools = call(2, "tools/list").getAsJsonArray("tools").map { it.asJsonObject }
        assertEquals(
            listOf("todo_list", "todo_get", "todo_create", "todo_update", "todo_comment", "todo_batch", "todo_decided", "todo_delete"),
            tools.map { it.get("name").asString }.take(8),
        )
        tools.forEach { assertTrue(it.getAsJsonObject("inputSchema").getAsJsonObject("properties").has("project")) }
        assertEquals("array", tools[5].getAsJsonObject("inputSchema").getAsJsonObject("properties").getAsJsonObject("items").get("type").asString)
    }

    @Test
    fun `a created item is listed, and the instructions then name the project`() {
        assertTrue(tool(1, "todo_create", """{"title":"Fix the till","priority":"p1","effort":"s"}""").contains("created project"))
        val rows = JsonParser.parseString(tool(2, "todo_list", """{"compact":true}""")).asJsonArray
        assertEquals("Fix the till", rows[0].asJsonObject.get("title").asString)
        assertTrue(call(3, "initialize").get("instructions").asString.contains("The current project is shop"))
        val batch = tool(4, "todo_batch", """{"items":[{"title":"a"},{"id":"T-1","status":"fixed"}]}""")
        assertTrue(batch.contains("\"T-2\""))
        assertFalse(call(5, "tools/call", """{"name":"todo_get","arguments":{"id":"T-1"}}""").get("isError").asBoolean)
    }
}

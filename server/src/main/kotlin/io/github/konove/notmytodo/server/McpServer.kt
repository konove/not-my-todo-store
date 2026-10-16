package io.github.konove.notmytodo.server

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStream
import java.io.OutputStream

/**
 * An MCP server over stdio: one JSON-RPC message per line in, one per line out. It answers
 * initialize, ping, tools/list and tools/call; notifications are read and not answered.
 */
class McpServer(
    private val name: String,
    private val version: String,
    private val instructions: () -> String,
    private val tools: List<Tool>,
) {
    fun serve(input: InputStream, output: OutputStream) {
        val reader = input.bufferedReader(Charsets.UTF_8)
        val writer = output.bufferedWriter(Charsets.UTF_8)
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isBlank()) continue
            val reply = handle(line) ?: continue
            writer.write(reply)
            writer.write("\n")
            writer.flush()
        }
    }

    /** The answer to one message, or null when it needs none. */
    fun handle(line: String): String? {
        val message = try {
            JsonParser.parseString(line).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        } ?: return error(JsonNull.INSTANCE, -32700, "Parse error")
        val id = message.get("id")?.takeUnless { it.isJsonNull } ?: return null
        // An answer to a request of ours; this server makes none.
        val method = message.get("method")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val params = message.get("params")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        return try {
            when (method) {
                "initialize" -> result(id, initialize(params))
                "ping" -> result(id, JsonObject())
                "tools/list" -> result(id, JsonObject().apply { add("tools", JsonArray().also { a -> tools.forEach { a.add(describe(it)) } }) })
                "tools/call" -> result(id, call(params))
                else -> error(id, -32601, "Method not found")
            }
        } catch (e: Exception) {
            // One failed request must not end the server; the agent gets the failure as its answer.
            System.err.println("$method failed")
            e.printStackTrace()
            error(id, -32603, "Internal error: $e")
        }
    }

    private fun initialize(params: JsonObject): JsonObject = JsonObject().apply {
        val asked = params.get("protocolVersion")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        addProperty("protocolVersion", asked ?: DEFAULT_PROTOCOL)
        add("capabilities", JsonObject().apply { add("tools", JsonObject().apply { addProperty("listChanged", false) }) })
        add("serverInfo", JsonObject().apply {
            addProperty("name", name)
            addProperty("version", version)
        })
        addProperty("instructions", instructions())
    }

    private fun describe(tool: Tool): JsonObject = JsonObject().apply {
        addProperty("name", tool.name)
        addProperty("description", tool.description)
        add("inputSchema", tool.schema())
    }

    private fun call(params: JsonObject): JsonObject {
        val name = params.get("name")?.takeIf { it.isJsonPrimitive }?.asString
        val tool = tools.firstOrNull { it.name == name } ?: return text("there is no tool $name", error = true)
        val arguments = params.get("arguments")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        return try {
            text(tool.run(Args(arguments, tool.params)), error = false)
        } catch (e: ToolError) {
            text(e.message ?: "the tool failed", error = true)
        } catch (e: Throwable) {
            System.err.println("$name failed")
            e.printStackTrace()
            text("internal error in $name: $e", error = true)
        }
    }

    private fun text(text: String, error: Boolean): JsonObject = JsonObject().apply {
        add("content", JsonArray().also { a ->
            a.add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", text)
            })
        })
        addProperty("isError", error)
    }

    private fun result(id: JsonElement, result: JsonObject): String = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id)
        add("result", result)
    }.toString()

    private fun error(id: JsonElement, code: Int, message: String): String = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id)
        add("error", JsonObject().apply {
            addProperty("code", code)
            addProperty("message", message)
        })
    }.toString()

    private companion object {
        const val DEFAULT_PROTOCOL = "2025-06-18"
    }
}

package io.github.konove.notmytodo.server

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** What a tool says went wrong, in words the agent can act on. It is answered as a tool result with isError. */
class ToolError(message: String) : Exception(message)

enum class ParamType(val json: String) { STRING("string"), INTEGER("integer"), BOOLEAN("boolean"), OBJECTS("array") }

data class Param(val name: String, val type: ParamType, val description: String, val required: Boolean = false)

class Tool(val name: String, val description: String, val params: List<Param>, val run: (Args) -> String) {
    fun schema(): JsonObject {
        val properties = JsonObject()
        for (p in params) {
            properties.add(p.name, JsonObject().apply {
                addProperty("type", p.type.json)
                addProperty("description", p.description)
                if (p.type == ParamType.OBJECTS) add("items", JsonObject().apply { addProperty("type", "object") })
            })
        }
        return JsonObject().apply {
            addProperty("type", "object")
            add("properties", properties)
            add("required", JsonArray().also { a -> params.filter { it.required }.forEach { a.add(it.name) } })
        }
    }
}

/**
 * The arguments of one call. Agents send numbers as text and the other way round, so a value is
 * taken in either form; a parameter the tool does not have is an error, since it is usually a typo.
 */
class Args(private val o: JsonObject, params: List<Param>) {
    init {
        val known = params.map { it.name }.toSet()
        o.keySet().firstOrNull { it !in known }?.let {
            throw ToolError("unknown parameter $it: this tool takes ${known.joinToString(", ")}")
        }
    }

    private fun value(name: String): JsonElement? = o.get(name)?.takeUnless { it.isJsonNull }

    fun string(name: String): String? = value(name)?.let {
        if (it.isJsonPrimitive) it.asString else throw ToolError("$name must be a text")
    }

    fun required(name: String): String = string(name) ?: throw ToolError("$name is required")

    fun int(name: String): Int? = value(name)?.let {
        (if (it.isJsonPrimitive) it.asString.trim().toIntOrNull() else null) ?: throw ToolError("$name must be a whole number")
    }

    fun boolOrNull(name: String): Boolean? = value(name)?.let {
        when (if (it.isJsonPrimitive) it.asString.trim().lowercase() else null) {
            "true" -> true
            "false" -> false
            else -> throw ToolError("$name must be true or false")
        }
    }

    fun bool(name: String): Boolean = boolOrNull(name) ?: false

    /** An array of objects as JSON text; a text that holds the same JSON is taken as well. */
    fun entries(name: String): String {
        val v = value(name) ?: throw ToolError("$name is required")
        return if (v.isJsonPrimitive && v.asJsonPrimitive.isString) v.asString else v.toString()
    }
}

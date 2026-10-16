package io.github.konove.notmytodo.store

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import io.github.konove.notmytodo.model.TodoFormatException

/** The items file a project was made from, so that it is imported once. */
data class Imported(val path: String, val sha256: String)

/** What `project.json` says about one project. [unknown] holds the fields this version does not know. */
data class ProjectRecord(
    val key: String,
    val remotes: List<String> = emptyList(),
    val checkouts: List<String> = emptyList(),
    val nextId: Int = 1,
    val importedFrom: Imported? = null,
    val version: Int = ProjectJson.VERSION,
    val unknown: Map<String, JsonElement> = emptyMap(),
)

object ProjectJson {
    /** The layout of a project folder this version writes. A newer one is read and not written. */
    const val VERSION = 1

    private val keys = setOf("version", "key", "remotes", "checkouts", "nextId", "importedFrom")
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun encode(r: ProjectRecord): String {
        val o = JsonObject()
        o.addProperty("version", r.version)
        o.addProperty("key", r.key)
        o.add("remotes", JsonArray().also { a -> r.remotes.forEach(a::add) })
        o.add("checkouts", JsonArray().also { a -> r.checkouts.forEach(a::add) })
        o.addProperty("nextId", r.nextId)
        r.importedFrom?.let {
            o.add("importedFrom", JsonObject().apply {
                addProperty("path", it.path)
                addProperty("sha256", it.sha256)
            })
        }
        r.unknown.forEach { (k, v) -> if (!o.has(k)) o.add(k, v) }
        return gson.toJson(o) + "\n"
    }

    /** The record of the project in folder [key]: the folder's name is its key, whatever the file says. */
    fun decode(text: String, key: String): ProjectRecord {
        val root = try {
            JsonParser.parseString(text)
        } catch (e: JsonParseException) {
            throw TodoFormatException("project.json is not valid JSON: ${e.message}")
        }
        if (!root.isJsonObject) throw TodoFormatException("project.json must be a JSON object")
        val o = root.asJsonObject
        fun strings(name: String): List<String> =
            o.get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }.orEmpty()
        fun int(name: String): Int? = o.get(name)?.takeIf { it.isJsonPrimitive }?.let {
            try {
                it.asInt
            } catch (_: NumberFormatException) {
                throw TodoFormatException("\"$name\" in project.json must be a whole number")
            }
        }
        val imported = o.get("importedFrom")?.takeIf { it.isJsonObject }?.asJsonObject?.let { i ->
            val path = i.get("path")?.takeIf { it.isJsonPrimitive }?.asString
            val sha = i.get("sha256")?.takeIf { it.isJsonPrimitive }?.asString
            if (path != null && sha != null) Imported(path, sha) else null
        }
        return ProjectRecord(
            key = key, remotes = strings("remotes"), checkouts = strings("checkouts"), nextId = int("nextId") ?: 1,
            importedFrom = imported, version = int("version") ?: VERSION,
            unknown = o.entrySet().filter { it.key !in keys }.associate { it.key to it.value },
        )
    }
}

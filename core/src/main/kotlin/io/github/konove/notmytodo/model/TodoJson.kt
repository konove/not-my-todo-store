package io.github.konove.notmytodo.model

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

class TodoFormatException(message: String) : Exception(message)

object TodoJson {
    /**
     * The file version this plugin writes. Version 1 is what 0.1.0 wrote; 0.1.0 drops the fields it
     * does not know and refuses any other version. Version 2 has the same layout, and promises that
     * every reader keeps unknown fields, so a new field needs no new version. A version 1 file is
     * still read, and becomes version 2 when it is next written. Both wrote one "anchor" object where
     * there now is an "anchors" array; that is read as the only anchor and not written again.
     */
    const val NEWEST_VERSION = 2

    private val fileKeys = setOf("version", "nextId", "items")
    private val itemKeys = setOf(
        "id", "title", "details", "priority", "tags", "status", "author", "created", "updated", "comments", "anchor", "anchors",
        "blockedBy", "duplicateOf", "parent", "source", "fixedIn", "resolution",
        "toDecide", "decisions", "effort",
    )
    private val decisionKeys = setOf("question", "options", "answer", "time")
    private val commentKeys = setOf("author", "time", "text")
    private val anchorKeys = setOf("path", "startLine", "endLine", "text", "before", "after", "lost")

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()

    fun toText(element: JsonElement): String = gson.toJson(element)

    fun encode(file: TodoFile): String {
        val root = JsonObject()
        root.addProperty("version", file.version)
        root.addProperty("nextId", file.nextId)
        val items = JsonArray()
        file.items.sortedBy { it.number }.forEach { items.add(encodeItem(it)) }
        root.add("items", items)
        root.addUnknown(file.unknown)
        return toText(root) + "\n"
    }

    fun encodeItem(item: TodoItem): JsonObject {
        val o = JsonObject()
        o.addProperty("id", item.id)
        o.addProperty("title", item.title)
        o.addProperty("details", item.details)
        o.addProperty("priority", item.priority.json)
        item.effort?.let { o.addProperty("effort", it.json) }
        o.add("tags", strings(item.tags))
        o.addProperty("status", item.status.json)
        o.addProperty("author", item.author.json)
        o.addProperty("created", item.created)
        o.addProperty("updated", item.updated)
        if (item.blockedBy.isNotEmpty()) o.add("blockedBy", strings(item.blockedBy))
        item.duplicateOf?.let { o.addProperty("duplicateOf", it) }
        item.parent?.let { o.addProperty("parent", it) }
        item.source?.let { o.addProperty("source", it) }
        item.fixedIn?.let { o.addProperty("fixedIn", it) }
        item.resolution?.let { o.addProperty("resolution", it) }
        item.toDecide?.let { o.addProperty("toDecide", it) }
        if (item.decisions.isNotEmpty()) {
            val decisions = JsonArray()
            item.decisions.forEach { d ->
                val entry = JsonObject()
                entry.addProperty("question", d.question)
                entry.add("options", strings(d.options))
                entry.addProperty("answer", d.answer)
                entry.addProperty("time", d.time)
                entry.addUnknown(d.unknown)
                decisions.add(entry)
            }
            o.add("decisions", decisions)
        }
        if (item.comments.isNotEmpty()) {
            val comments = JsonArray()
            item.comments.forEach { c ->
                val co = JsonObject()
                co.addProperty("author", c.author.json)
                co.addProperty("time", c.time)
                co.addProperty("text", c.text)
                co.addUnknown(c.unknown)
                comments.add(co)
            }
            o.add("comments", comments)
        }
        if (item.anchors.isNotEmpty()) {
            val anchors = JsonArray()
            item.anchors.forEach { anchors.add(encodeAnchor(it)) }
            o.add("anchors", anchors)
        }
        o.addUnknown(item.unknown)
        return o
    }

    /** An anchor on a whole file has only its path and whether it is lost. */
    private fun encodeAnchor(a: Anchor): JsonObject {
        val ao = JsonObject()
        ao.addProperty("path", a.path)
        if (!a.isFile) {
            ao.addProperty("startLine", a.startLine)
            ao.addProperty("endLine", a.endLine)
            ao.addProperty("text", a.text)
            ao.add("before", strings(a.before))
            ao.add("after", strings(a.after))
        }
        ao.addProperty("lost", a.lost)
        ao.addUnknown(a.unknown)
        return ao
    }

    fun decode(text: String): TodoFile {
        if (text.isBlank()) return TodoFile()
        val root = try {
            JsonParser.parseString(text)
        } catch (e: JsonParseException) {
            throw TodoFormatException("not valid JSON: ${e.message}")
        }
        if (!root.isJsonObject) throw TodoFormatException("the top level must be a JSON object")
        val obj = root.asJsonObject
        val version = obj.int("version") ?: 1
        if (version !in 1..NEWEST_VERSION) throw TodoFormatException("unsupported version $version")
        val array = obj.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        val items = array.mapIndexed { index, element -> decodeItem(element, index) }
        val duplicate = items.groupBy { it.id }.entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) throw TodoFormatException("id ${duplicate.key} is used more than once")
        val highest = items.maxOfOrNull { it.number } ?: 0
        val nextId = maxOf(obj.int("nextId") ?: 1, highest + 1)
        return TodoFile(NEWEST_VERSION, nextId, items.sortedBy { it.number }, obj.unknown(fileKeys))
    }

    private fun decodeItem(element: JsonElement, index: Int): TodoItem {
        if (!element.isJsonObject) throw TodoFormatException("item ${index + 1} is not an object")
        val o = element.asJsonObject
        val id = o.str("id") ?: throw TodoFormatException("item ${index + 1} has no id")
        if (ItemId.parse(id) == null) throw TodoFormatException("item ${index + 1} has a bad id \"$id\" (expected T-1, T-2, ...)")
        val title = o.str("title")?.trim().orEmpty()
        if (title.isEmpty()) throw TodoFormatException("item $id has no title")
        val priority = o.str("priority")?.let {
            Priority.fromJson(it) ?: throw TodoFormatException("item $id has a bad priority \"$it\"")
        } ?: Priority.P2
        val effort = o.text("effort")?.let {
            Effort.fromJson(it) ?: throw TodoFormatException("item $id has a bad effort \"$it\"")
        }
        val status = o.str("status")?.let {
            Status.fromJson(it) ?: throw TodoFormatException("item $id has a bad status \"$it\"")
        } ?: Status.OPEN
        val author = o.str("author")?.let {
            Author.fromJson(it) ?: throw TodoFormatException("item $id has a bad author \"$it\"")
        } ?: Author.USER
        val comments = o.get("comments")?.takeIf { it.isJsonArray }?.asJsonArray?.map { decodeComment(it, id) }.orEmpty()
        val decisions = o.get("decisions")?.takeIf { it.isJsonArray }?.asJsonArray?.map { decodeDecision(it, id) }.orEmpty()
        val anchors = o.get("anchors")?.takeIf { it.isJsonArray }?.asJsonArray?.map {
            if (!it.isJsonObject) throw TodoFormatException("item $id has an anchor that is not an object")
            decodeAnchor(it.asJsonObject, id)
        } ?: listOfNotNull(o.get("anchor")?.takeIf { it.isJsonObject }?.asJsonObject?.let { decodeAnchor(it, id) })
        return TodoItem(
            id = id, title = title, details = o.str("details").orEmpty(), priority = priority,
            tags = Tags.normalizeAll(o.strList("tags")), status = status, author = author,
            created = o.str("created").orEmpty(), updated = o.str("updated").orEmpty(), comments = comments,
            anchors = anchors, blockedBy = o.strList("blockedBy").mapNotNull(ItemId::normalize).distinct(),
            duplicateOf = o.str("duplicateOf")?.let(ItemId::normalize), parent = o.str("parent")?.let(ItemId::normalize),
            source = o.text("source"), fixedIn = o.text("fixedIn"), resolution = o.text("resolution"),
            toDecide = o.text("toDecide"), decisions = decisions, effort = effort,
            unknown = o.unknown(itemKeys),
        )
    }

    private fun decodeComment(element: JsonElement, id: String): Comment {
        if (!element.isJsonObject) throw TodoFormatException("item $id has a comment that is not an object")
        val o = element.asJsonObject
        val author = o.str("author")?.let {
            Author.fromJson(it) ?: throw TodoFormatException("item $id has a comment with a bad author \"$it\"")
        } ?: Author.USER
        return Comment(author, o.str("time").orEmpty(), o.str("text").orEmpty(), o.unknown(commentKeys))
    }

    private fun decodeDecision(element: JsonElement, id: String): Decision {
        if (!element.isJsonObject) throw TodoFormatException("item $id has a decision that is not an object")
        val o = element.asJsonObject
        return Decision(
            o.str("question").orEmpty(), o.strList("options"), o.str("answer").orEmpty(), o.str("time").orEmpty(), o.unknown(decisionKeys),
        )
    }

    private fun decodeAnchor(o: JsonObject, id: String): Anchor {
        val path = o.str("path")?.takeIf { it.isNotBlank() } ?: throw TodoFormatException("item $id has an anchor with no path")
        val lost = o.get("lost")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
        val start = o.int("startLine") ?: return Anchor.file(path).copy(lost = lost, unknown = o.unknown(anchorKeys))
        val end = o.int("endLine") ?: start
        if (start < 1 || end < start) throw TodoFormatException("item $id has a bad anchor line range $start-$end")
        return Anchor(path, start, end, o.str("text").orEmpty(), o.strList("before"), o.strList("after"), lost, o.unknown(anchorKeys))
    }

    private fun strings(values: List<String>): JsonArray = JsonArray().also { a -> values.forEach(a::add) }

    private fun JsonObject.unknown(known: Set<String>): Map<String, JsonElement> =
        entrySet().filter { it.key !in known }.associate { it.key to it.value }

    private fun JsonObject.addUnknown(fields: Map<String, JsonElement>) {
        fields.forEach { (key, value) -> if (!has(key)) add(key, value) }
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    /** A text that says something: null when it is missing or blank. */
    private fun JsonObject.text(key: String): String? = str(key)?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(key: String): Int? {
        val e = get(key)?.takeIf { it.isJsonPrimitive } ?: return null
        return try {
            e.asInt
        } catch (_: NumberFormatException) {
            throw TodoFormatException("\"$key\" must be a whole number")
        }
    }

    private fun JsonObject.strList(key: String): List<String> =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }
            ?: emptyList()
}

package io.github.konove.notmytodo.server

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import io.github.konove.notmytodo.anchor.AnchorResolver
import io.github.konove.notmytodo.model.Anchor
import io.github.konove.notmytodo.model.Author
import io.github.konove.notmytodo.model.Decision
import io.github.konove.notmytodo.model.Effort
import io.github.konove.notmytodo.model.ItemRef
import io.github.konove.notmytodo.model.Links
import io.github.konove.notmytodo.model.Priority
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.Tags
import io.github.konove.notmytodo.model.TodoItem
import io.github.konove.notmytodo.model.TodoJson
import io.github.konove.notmytodo.store.Draft
import io.github.konove.notmytodo.store.ItemStore
import io.github.konove.notmytodo.store.StoreException

/** Every list filter of todo_list, as the tool takes them. */
data class ListFilter(
    val status: String? = null, val tags: String? = null, val priority: String? = null, val path: String? = null,
    val withoutTags: String? = null, val text: String? = null, val compact: Boolean = false, val lost: Boolean = false,
    val blocked: Boolean? = null, val parent: String? = null, val effort: String? = null, val anyText: String? = null,
)

/** What the MCP tools do, as plain functions returning JSON text. Each call finds its project anew. */
class TodoTools(val workspace: Workspace) {
    fun list(
        status: String? = null, tags: String? = null, priority: String? = null, path: String? = null,
        withoutTags: String? = null, text: String? = null, compact: Boolean = false, lost: Boolean = false,
        blocked: Boolean? = null, parent: String? = null, effort: String? = null, anyText: String? = null,
        project: String? = null,
    ): String {
        val filter = ListFilter(status, tags, priority, path, withoutTags, text, compact, lost, blocked, parent, effort, anyText)
        val scope = workspace.scope(project) ?: return "[]"
        return Call(scope, workspace).list(filter)
    }

    fun get(id: String, full: Boolean = false, project: String? = null): String =
        workspace.target(id, project).let { (scope, bare) -> Call(scope, workspace).get(bare, full) }

    fun create(
        title: String, details: String?, priority: String?, tags: String?,
        path: String?, startLine: Int?, endLine: Int?, places: String? = null,
        blockedBy: String? = null, duplicateOf: String? = null, parent: String? = null, source: String? = null,
        toDecide: String? = null, effort: String? = null, full: Boolean = false, project: String? = null,
    ): String {
        val scope = workspace.scope(project, create = true)!!
        val reply = Call(scope, workspace).create(title, details, priority, tags, path, startLine, endLine, places, blockedBy, duplicateOf, parent, source, toDecide, effort, full)
        return scope.notice?.let { notice -> noted(reply, notice) } ?: reply
    }

    fun update(
        id: String, title: String?, details: String?, priority: String?, tags: String?, status: String?,
        path: String? = null, startLine: Int? = null, endLine: Int? = null, places: String? = null,
        blockedBy: String? = null, duplicateOf: String? = null, parent: String? = null,
        source: String? = null, fixedIn: String? = null, resolution: String? = null, toDecide: String? = null,
        effort: String? = null, full: Boolean = false, project: String? = null,
    ): String = workspace.target(id, project).let { (scope, bare) ->
        Call(scope, workspace).update(bare, title, details, priority, tags, status, path, startLine, endLine, places, blockedBy, duplicateOf, parent, source, fixedIn, resolution, toDecide, effort, full)
    }

    fun batch(items: String, project: String? = null): String {
        val scope = workspace.scope(project, create = true)!!
        val reply = Call(scope, workspace).batch(items)
        return scope.notice?.let { "$it\n$reply" } ?: reply
    }

    fun comment(id: String, text: String, full: Boolean = false, project: String? = null): String =
        workspace.target(id, project).let { (scope, bare) -> Call(scope, workspace).comment(bare, text, full) }

    fun delete(id: String, project: String? = null): String =
        workspace.target(id, project).let { (scope, bare) -> Call(scope, workspace).delete(bare) }

    fun decided(id: String, decisions: String, full: Boolean = false, project: String? = null): String =
        workspace.target(id, project).let { (scope, bare) -> Call(scope, workspace).decided(bare, decisions, full) }

    /** [reply], one JSON object, with [notice] added to it; a full item is JSON as well. */
    private fun noted(reply: String, notice: String): String {
        val json = JsonParser.parseString(reply).asJsonObject
        json.addProperty("notice", notice)
        return if (reply.contains('\n')) TodoJson.toText(json) else json.toString()
    }
}

/** One tool call, in the project of [scope]. */
private class Call(private val scope: Scope, private val workspace: Workspace) {
    private val store get() = scope.items

    fun list(filter: ListFilter): String {
        val partOf = filter.parent?.takeIf { it.isNotBlank() }?.let(::parseId)
        val statuses = words(filter.status).map(::parseStatus)
        val wantedPriority = filter.priority?.takeIf { it.isNotBlank() }?.let(::parsePriority)
        val wantedEffort = filter.effort?.takeIf { it.isNotBlank() }?.let(::parseEffort)
        val wanted = Tags.parseList(filter.tags.orEmpty())
        val unwanted = Tags.parseList(filter.withoutTags.orEmpty())
        val under = filter.path?.takeIf { it.isNotBlank() }?.let(::directory)
        val needles = words(filter.text)
        val anyOf = phrases(filter.anyText)
        val all = asNow(readable())
        val links = links(all)
        val found = all.filter { item ->
            (statuses.isEmpty() || item.status in statuses) &&
                (wantedPriority == null || links.priority(item) == wantedPriority) &&
                (wantedEffort == null || item.effort == wantedEffort) &&
                item.tags.containsAll(wanted) && unwanted.none { it in item.tags } &&
                (under == null || item.anchors.any { isUnder(it.path, under) }) &&
                (!filter.lost || item.anyLost) &&
                (filter.blocked == null || links.isBlocked(item) == filter.blocked) &&
                (partOf == null || item.parent == partOf) &&
                needles.all { item.says(it) } && (anyOf.isEmpty() || anyOf.any { item.says(it) })
        }
        // One item per line: pretty printing would triple the size of a list that is meant to be read whole.
        if (filter.compact) return rows(found.map { compactRow(it, links) })
        fixes(found)
        val array = JsonArray()
        found.forEach { array.add(summary(it, links)) }
        return TodoJson.toText(array)
    }

    fun get(id: String, full: Boolean = false): String {
        val item = asNow(listOf(item(id))).single()
        fixes(listOf(item))
        val links = links(readable())
        val json = encoded(item, links)
        if (links.isBlocked(item)) json.addProperty("blocked", true)
        links.children(id).takeIf { it.isNotEmpty() }?.let { parts ->
            json.add("children", JsonArray().also { a -> parts.forEach { a.add(it.id) } })
            json.addProperty("parts", countsOf(parts))
        }
        // Written in the order of item.anchors.
        val encoded = json.getAsJsonArray("anchors")
        item.anchors.forEachIndexed { index, anchor ->
            if (anchor.isFile) return@forEachIndexed
            val lines = if (anchor.lost) null else AnchorsNow.read(scope.checkout, anchor.path)?.let(AnchorResolver::lines)
            val code = lines?.takeIf { anchor.endLine <= it.size }?.subList(anchor.startLine - 1, anchor.endLine)?.joinToString("\n")
            encoded[index].asJsonObject.apply {
                if (!full) {
                    // The code says what the stored text says. Without it, the text is the one clue to what the item was attached to.
                    if (code != null) remove("text")
                    remove("before")
                    remove("after")
                }
                if (code != null) addProperty("code", code)
            }
        }
        if (scope.checkout == null && item.anchors.isNotEmpty()) {
            val remotes = workspace.store.project(scope.key)?.remotes.orEmpty()
            json.addProperty(
                "codeNotRead",
                "this machine has no checkout of ${scope.key}" + if (remotes.isEmpty()) "" else "; its remotes: ${remotes.joinToString(", ")}",
            )
        }
        return TodoJson.toText(json)
    }

    fun create(
        title: String, details: String?, priority: String?, tags: String?,
        path: String?, startLine: Int?, endLine: Int?, places: String? = null,
        blockedBy: String? = null, duplicateOf: String? = null, parent: String? = null, source: String? = null,
        toDecide: String? = null, effort: String? = null, full: Boolean = false,
    ): String {
        val draft = draft(title, details, priority, tags, path, startLine, endLine, places, blockedBy, duplicateOf, parent, source, toDecide, effort)
        return reply(storeCall { store.create(draft) }, full)
    }

    private fun draft(
        title: String, details: String?, priority: String?, tags: String?,
        path: String?, startLine: Int?, endLine: Int?, places: String?,
        blockedBy: String?, duplicateOf: String?, parent: String?, source: String?, toDecide: String?, effort: String?,
    ): Draft {
        if (title.isBlank()) throw ToolError("title must not be empty")
        val parsedPriority = priority?.let(::parsePriority) ?: Priority.P2
        if (path == null && (startLine != null || endLine != null)) {
            throw ToolError("path is required when startLine or endLine is given")
        }
        val first = path?.let { buildAnchor(Place(filePath(it), startLine, endLine)) }
        val anchors = (listOfNotNull(first) + parsePlaces(places).map(::buildAnchor)).distinctBy(Anchor::place)
        val draft = Draft(
            title, details.orEmpty(), parsedPriority, waiting(Tags.parseList(tags.orEmpty()), toDecide), Author.AGENT, anchors,
            source = source?.let(::named), toDecide = toDecide, effort = effort?.takeIf { it.isNotBlank() }?.let(::parseEffort),
        )
        return linked(draft, blockedBy, duplicateOf, parent)
    }

    /** The [draft] with its links to other items, each given as a tool takes it. */
    private fun linked(draft: Draft, blockedBy: String?, duplicateOf: String?, parent: String?): Draft = draft.copy(
        blockedBy = parseIds(blockedBy.orEmpty()),
        duplicateOf = duplicateOf?.takeIf { it.isNotBlank() }?.let(::parseId),
        parent = parent?.takeIf { it.isNotBlank() }?.let(::parseId),
    )

    /**
     * [source] with the names in it, HEAD and the like, as the commits they stand for now: one name, or
     * each end of a range. An id is left as it is, and so is what git does not know, since a source
     * may name a run in words.
     */
    private fun named(source: String): String {
        val commits = scope.fixes ?: return source
        val range = RANGE.matchEntire(source.trim()) ?: return commits.resolve(source) ?: source
        val (from, dots, to) = range.destructured
        val first = commits.resolve(from)
        val last = commits.resolve(to)
        return if (first == null && last == null) source else "${first ?: from}$dots${last ?: to}"
    }

    /** The [tags] of an item that is told what is [toDecide]: it waits for the user from then on. */
    private fun waiting(tags: List<String>, toDecide: String?): List<String> =
        if (toDecide.isNullOrBlank()) tags else (tags + Tags.NEEDS_DECISION).distinct()

    fun update(
        id: String, title: String?, details: String?, priority: String?, tags: String?, status: String?,
        path: String? = null, startLine: Int? = null, endLine: Int? = null, places: String? = null,
        blockedBy: String? = null, duplicateOf: String? = null, parent: String? = null,
        source: String? = null, fixedIn: String? = null, resolution: String? = null, toDecide: String? = null,
        effort: String? = null, full: Boolean = false,
    ): String {
        val edit = edit(title, details, priority, tags, status, blockedBy, duplicateOf, parent, source, fixedIn, resolution, toDecide, effort)
        // Compared as the code is now, so that lines that only moved are not reported as changed.
        val before = asNow(listOf(item(id))).single()
        val anchors = placed(id, path, startLine, endLine, places)
        val saved = storeCall {
            store.update(id) { old ->
                val edited = edit(AnchorsNow.of(old, scope.checkout))
                if (anchors == null) edited else edited.copy(anchors = anchors(edited.anchors).distinctBy(Anchor::place))
            }
        }
        return reply(saved, full, changed(before, saved))
    }

    /**
     * What path, startLine and endLine, or places, do to the anchors of item [id]: null when none of
     * them is passed. The files are read here, so a place that is wrong is found before anything is saved.
     */
    private fun placed(id: String, path: String?, startLine: Int?, endLine: Int?, places: String?): ((List<Anchor>) -> List<Anchor>)? {
        val moves = path != null || startLine != null || endLine != null
        if (moves && places != null) throw ToolError("pass either places or path, startLine and endLine, not both")
        if (places != null) {
            // The places an item has are compared as they are now in the files.
            val current = asNow(listOf(item(id))).single().anchors
            // A lost anchor is not kept: its place is passed again to say that the code is there now.
            val wanted = parsePlaces(places).map { place -> current.firstOrNull { place.isAt(it) && !it.lost } ?: buildAnchor(place) }
            return { wanted }
        }
        if (!moves) return null
        val current = item(id).anchors
        val file = path?.let(::filePath)
        val index = when {
            current.size < 2 -> 0
            file == null -> throw ToolError(
                "$id is attached to ${current.size} places: give path to say which one moves, or places to set them all"
            )
            else -> current.indices.singleOrNull { current[it].path == file } ?: throw ToolError(
                "$id does not have exactly one place in $file: pass places to set the whole list of places"
            )
        }
        val anchorPath = file ?: current.firstOrNull()?.path
            ?: throw ToolError("path is required to attach $id to code: it is a plain note")
        // Built anew even when the lines are the ones it has, which finds a lost anchor again.
        val anchor = buildAnchor(Place(anchorPath, startLine, endLine))
        return { anchors -> if (index in anchors.indices) anchors.mapIndexed { i, old -> if (i == index) anchor else old } else anchors + anchor }
    }

    /** The change that the fields passed to an update make to an item. Fields left out stay as they are. */
    private fun edit(
        title: String? = null, details: String? = null, priority: String? = null, tags: String? = null, status: String? = null,
        blockedBy: String? = null, duplicateOf: String? = null, parent: String? = null,
        source: String? = null, fixedIn: String? = null, resolution: String? = null, toDecide: String? = null, effort: String? = null,
    ): (TodoItem) -> TodoItem {
        // An empty text takes the link away; a text left out leaves it as it is.
        val blockers = blockedBy?.let(::parseIds)
        val original = duplicateOf?.let { if (it.isBlank()) null else parseId(it) }
        val partOf = parent?.let { if (it.isBlank()) null else parseId(it) }
        val parsedPriority = priority?.let(::parsePriority)
        val parsedEffort = effort?.let { if (it.isBlank()) null else parseEffort(it) }
        val parsedStatus = status?.let(::parseStatus)
        val parsedTags = tags?.let(Tags::parseList)
        // HEAD and the like are stored as the commit they name now: later they would name another.
        val commit = fixedIn?.let { scope.fixes?.resolve(it) ?: it }
        val from = source?.let(::named)
        return {
            it.copy(
                title = title ?: it.title,
                details = details ?: it.details,
                priority = parsedPriority ?: it.priority,
                tags = waiting(parsedTags ?: it.tags, toDecide),
                status = parsedStatus ?: it.status,
                blockedBy = blockers ?: it.blockedBy,
                duplicateOf = if (duplicateOf != null) original else it.duplicateOf,
                parent = if (parent != null) partOf else it.parent,
                // The store reads an empty text as none.
                source = from ?: it.source,
                fixedIn = commit ?: it.fixedIn,
                resolution = resolution ?: it.resolution,
                toDecide = toDecide ?: it.toDecide,
                effort = if (effort != null) parsedEffort else it.effort,
            )
        }
    }

    /**
     * Creates and updates several items as one change. [items] is a JSON array of objects: one with
     * an id changes that item, one without is a new item. Nothing is saved when an entry is wrong.
     * A new item may carry a ref, and the entries after it name it as `$ref` where they take an id.
     */
    fun batch(items: String): String {
        val entries = try {
            JsonParser.parseString(items).takeIf { it.isJsonArray }?.asJsonArray
        } catch (_: JsonParseException) {
            null
        } ?: throw ToolError("items must be a JSON array of objects, for example [{\"title\": \"...\"}, {\"id\": \"T-3\", \"tags\": \"...\"}]")
        if (entries.isEmpty) throw ToolError("items is empty: pass at least one object")
        // Every entry is read before the first is saved, and the files of new items with it.
        val refs = HashMap<String, String>()
        val changes = entries.mapIndexed { index, entry -> inEntry(index) { change(entry, refs) } }
        val saved = storeCall {
            store.batch { batch -> changes.mapIndexed { index, change -> inEntry(index) { storeCall { change(batch) } } } }
        }
        val links = links(readable())
        return rows(asNow(saved).map { compactRow(it, links) })
    }

    private fun <T> inEntry(index: Int, block: () -> T): T = try {
        block()
    } catch (e: ToolError) {
        throw ToolError("entry ${index + 1}: ${e.message}")
    }

    /** The change one entry of a batch makes. [refs] are the ids of the new items made so far, by their ref. */
    private fun change(entry: JsonElement, refs: MutableMap<String, String>): (ItemStore.Batch) -> TodoItem {
        if (!entry.isJsonObject) throw ToolError("must be a JSON object")
        val o = entry.asJsonObject
        val updates = o.has("id")
        val known = if (updates) UPDATE_FIELDS else CREATE_FIELDS
        o.keySet().firstOrNull { it !in known }?.let { field ->
            throw ToolError(
                when {
                    updates && field == "ref" -> "ref is for a new item: an item that is there is named by its id"
                    !updates && field in UPDATE_FIELDS -> "$field cannot be set on a new item: leave it out, or give an id to change an item"
                    else -> "unknown field $field: ${if (updates) "an item to change" else "a new item"} takes ${known.joinToString(", ")}"
                }
            )
        }
        fun text(field: String): String? = o.get(field)?.takeUnless { it.isJsonNull }?.let { value ->
            when {
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString
                // Lists are written as an array as readily as a text.
                value.isJsonArray && field in LIST_FIELDS && value.asJsonArray.all { it.isJsonPrimitive } ->
                    value.asJsonArray.joinToString(",") { it.asString }
                else -> throw ToolError("$field must be a text")
            }
        }
        fun line(field: String): Int? = o.get(field)?.takeUnless { it.isJsonNull }?.let { value ->
            value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toIntOrNull()
                ?: throw ToolError("$field must be a whole number")
        }
        // The links are read when the entry is applied: only then do the new items before it have ids.
        val blockedBy = text("blockedBy")
        val duplicateOf = text("duplicateOf")
        val parent = text("parent")
        fun resolved(link: String?): String? = link?.replace(REF) { match ->
            refs[match.groupValues[1]] ?: throw ToolError("there is no new item with ref \"${match.groupValues[1]}\" before this entry")
        }
        if (updates) {
            val ref = ItemRef.parse(text("id") ?: throw ToolError("id must be a text"))
                ?: throw ToolError("id is not an item id: write T-1, T-2, ... or project/T-1")
            if (ref.project != null && ref.project != scope.key) {
                throw ToolError("${ref} is in project ${ref.project}: a batch changes the items of one project, ${scope.key}")
            }
            val id = ref.id
            val edit = edit(
                text("title"), text("details"), text("priority"), text("tags"), text("status"),
                source = text("source"), fixedIn = text("fixedIn"), resolution = text("resolution"), toDecide = text("toDecide"),
                effort = text("effort"),
            )
            val anchors = placed(id, text("path"), line("startLine"), line("endLine"), text("places"))
            // In a batch, what the agent has to say about an item goes with the change, in the same write.
            val comment = text("comment")
            if (comment != null && comment.isBlank()) throw ToolError("comment must not be empty")
            return { batch ->
                val links = edit(blockedBy = resolved(blockedBy), duplicateOf = resolved(duplicateOf), parent = resolved(parent))
                val saved = batch.update(id) { old ->
                    links(edit(AnchorsNow.of(old, scope.checkout))).let { if (anchors == null) it else it.copy(anchors = anchors(it.anchors).distinctBy(Anchor::place)) }
                }
                if (comment == null) saved else batch.comment(id, Author.AGENT, comment)
            }
        }
        val ref = text("ref")
        if (ref != null && !REF_NAME.matches(ref)) throw ToolError("ref must be a name made of letters, digits, - and _")
        val draft = draft(
            text("title") ?: throw ToolError("title is required for a new item"), text("details"), text("priority"),
            text("tags"), text("path"), line("startLine"), line("endLine"), text("places"),
            null, null, null, text("source"), text("toDecide"), text("effort"),
        )
        return { batch ->
            if (ref != null && ref in refs) throw ToolError("ref \"$ref\" is already the name of an entry before this one")
            batch.create(linked(draft, resolved(blockedBy), resolved(duplicateOf), resolved(parent))).also { if (ref != null) refs[ref] = it.id }
        }
    }

    fun comment(id: String, text: String, full: Boolean = false): String {
        if (text.isBlank()) throw ToolError("text must not be empty")
        return reply(storeCall { store.comment(id, Author.AGENT, text) }, full, listOf("comments"))
    }

    /**
     * Deletes an item that the agent made and that no other item refers to: one made to try the tools,
     * or made by mistake. Anything else is closed, not deleted, so that the user sees what became of it.
     */
    fun delete(id: String): String {
        val gone = storeCall {
            store.delete(parseId(id)) { item, all ->
                fun refuse(why: String): Nothing = throw StoreException("${item.id} cannot be deleted: $why")
                if (item.author != Author.AGENT) {
                    refuse("it was made by the user, and only the user deletes it. Close it with status \"wont_fix\" and a resolution instead")
                }
                val others = all.filter { it.id != item.id }
                fun ids(items: List<TodoItem>) = items.joinToString(", ") { it.id }
                others.filter { it.parent == item.id }.takeIf { it.isNotEmpty() }?.let {
                    refuse("it is the parent of ${ids(it)}. Delete those first, or take them out of it with parent \"\"")
                }
                others.filter { item.id in it.blockedBy }.takeIf { it.isNotEmpty() }?.let {
                    refuse("${ids(it)} ${if (it.size == 1) "is" else "are"} blocked by it. Take that link away first")
                }
                others.filter { it.duplicateOf == item.id }.takeIf { it.isNotEmpty() }?.let {
                    refuse("${ids(it)} ${if (it.size == 1) "is" else "are"} a duplicate of it. Take that link away first")
                }
            }
        }
        return JsonObject().apply {
            addProperty("deleted", gone.id)
            addProperty("title", gone.title)
        }.toString()
    }

    /**
     * Records what the user was asked about an item and answered. [decisions] is a JSON array of
     * objects with a question, the options offered, if any, and the answer.
     */
    fun decided(id: String, decisions: String, full: Boolean = false): String {
        val entries = try {
            JsonParser.parseString(decisions).takeIf { it.isJsonArray }?.asJsonArray
        } catch (_: JsonParseException) {
            null
        } ?: throw ToolError(
            "decisions must be a JSON array of objects, for example [{\"question\": \"...\", \"options\": [\"...\", \"...\"], \"answer\": \"...\"}]"
        )
        if (entries.isEmpty) throw ToolError("decisions is empty: pass at least one object")
        val parsed = entries.mapIndexed { index, entry -> inEntry(index) { decision(entry) } }
        val before = item(parseId(id))
        val saved = storeCall { store.decide(before.id, parsed) }
        return reply(saved, full, changed(before, saved))
    }

    private fun decision(entry: JsonElement): Decision {
        if (!entry.isJsonObject) throw ToolError("must be a JSON object")
        val o = entry.asJsonObject
        o.keySet().firstOrNull { it !in DECISION_FIELDS }?.let {
            throw ToolError("unknown field $it: a decision takes ${DECISION_FIELDS.joinToString(", ")}")
        }
        fun text(field: String): String =
            o.get(field)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotBlank() }
                ?: throw ToolError("$field is required and must be a text")
        val options = o.get("options")?.takeUnless { it.isJsonNull }?.let { value ->
            value.takeIf { it.isJsonArray && it.asJsonArray.all { e -> e.isJsonPrimitive } }?.asJsonArray?.map { it.asString }
                ?: throw ToolError("options must be an array of texts")
        }.orEmpty()
        return Decision(text("question"), options, text("answer"), "")
    }

    /** A place as a tool is given it: a file, with lines or, for the whole file, without. */
    private data class Place(val path: String, val startLine: Int?, val endLine: Int?) {
        fun isAt(anchor: Anchor): Boolean = anchor.path == path &&
            if (startLine == null) anchor.isFile else anchor.startLine == startLine && anchor.endLine == (endLine ?: startLine)
    }

    /** Reads places written `path`, `path:12` or `path:12-20`, separated by commas or line breaks. */
    private fun parsePlaces(places: String?): List<Place> =
        places.orEmpty().split(Regex("[,\\r\\n]+")).map { it.trim() }.filter { it.isNotEmpty() }.map { entry ->
            val lines = Regex("^(.+):(\\d+)(?:-(\\d+))?$").matchEntire(entry)
            when {
                lines != null -> {
                    val (file, start, end) = lines.destructured
                    Place(filePath(file), start.toIntOrNull() ?: badPlace(entry), if (end.isEmpty()) null else end.toIntOrNull() ?: badPlace(entry))
                }
                ':' in entry -> badPlace(entry)
                else -> Place(filePath(entry), null, null)
            }
        }

    private fun badPlace(entry: String): Nothing =
        throw ToolError("\"$entry\" is not a place: write path, path:12 or path:12-20")

    private fun buildAnchor(place: Place): Anchor {
        val path = place.path
        if (place.startLine == null && place.endLine != null) throw ToolError("startLine is required when endLine is given")
        val text = AnchorsNow.read(scope.checkout, path) ?: throw ToolError(
            if (scope.checkout == null) "project ${scope.key} has no checkout on this machine, so $path cannot be read"
            else "file not found in the project: $path (use a path relative to the project root, ${scope.checkout})"
        )
        val start = place.startLine ?: return Anchor.file(path)
        val end = place.endLine ?: start
        val count = AnchorResolver.lines(text).size
        if (start < 1 || end < start || end > count) {
            throw ToolError("lines $start-$end are outside $path, which has $count lines")
        }
        return AnchorResolver.capture(path, text, start, end)
    }

    /**
     * What a tool that changes one item answers: one line that shows the change landed, with the
     * fields it [changed], since the agent knows the rest of the item. A short field that changed is
     * shown as it was saved, so that a wrong value is seen without a todo_get. With [full], the whole item.
     */
    private fun reply(saved: TodoItem, full: Boolean, changed: List<String>? = null): String {
        val item = asNow(listOf(saved)).single()
        val links = links(readable())
        if (full) return TodoJson.toText(encoded(item, links))
        val row = compactRow(item, links)
        changed?.let { names ->
            for (name in names) {
                val value = when (name) {
                    "effort" -> item.effort?.json
                    "fixedIn" -> item.fixedIn
                    "source" -> item.source
                    "parent" -> item.parent
                    "duplicateOf" -> item.duplicateOf
                    // Every blocker it was saved with, where the row of a list has only those still in the way.
                    "blockedBy" -> item.blockedBy.takeIf { it.isNotEmpty() }?.joinToString(" ")
                    // status, priority and tags are in every row; the long fields are named only.
                    else -> continue
                }
                row.addProperty(name, value)
            }
            row.add("changed", JsonArray().also { a -> names.forEach(a::add) })
        }
        return row.toString()
    }

    /** The fields of an item that differ between [before] and [after], by their names in its JSON. */
    private fun changed(before: TodoItem, after: TodoItem): List<String> {
        val was = TodoJson.encodeItem(before)
        val now = TodoJson.encodeItem(after)
        return (was.keySet() + now.keySet()).filter { it != "updated" && was.get(it) != now.get(it) }
    }

    /**
     * An item as JSON, with the commit that fixed it when git knows it and the item does not say, and
     * with the priority of its open parts when it has any.
     */
    private fun encoded(item: TodoItem, links: Links): JsonObject =
        TodoJson.encodeItem(links.shown(item).let { scope.fixes?.shown(it) ?: it })

    /** Asks git which commits fixed [items], when one of them is closed without saying so itself. */
    private fun fixes(items: List<TodoItem>) {
        if (scope.fixes?.let { f -> items.any(f::derives) } == true) scope.fixes.refresh()
    }

    private fun summary(item: TodoItem, links: Links): JsonObject {
        val json = encoded(item, links)
        if (links.isBlocked(item)) json.addProperty("blocked", true)
        json.getAsJsonArray("anchors")?.forEach {
            it.asJsonObject.apply {
                remove("text")
                remove("before")
                remove("after")
            }
        }
        return json
    }

    private fun compactRow(item: TodoItem, links: Links): JsonObject {
        val json = JsonObject()
        json.addProperty("id", item.id)
        json.addProperty("title", item.title)
        json.addProperty("priority", links.priority(item).json)
        item.effort?.let { json.addProperty("effort", it.json) }
        json.addProperty("status", item.status.json)
        json.add("tags", JsonArray().also { a -> item.tags.forEach(a::add) })
        if (item.anchors.isNotEmpty()) json.addProperty("at", item.anchors.joinToString(", ") { it.place })
        if (item.anyLost) json.addProperty("lost", true)
        // Only the blockers still in the way: a closed one no longer says anything about this item.
        links.openBlockerIds(item).takeIf { it.isNotEmpty() }?.let { json.addProperty("blockedBy", it.joinToString(" ")) }
        item.duplicateOf?.let { json.addProperty("duplicateOf", it) }
        item.parent?.let { json.addProperty("parent", it) }
        links.children(item.id).takeIf { it.isNotEmpty() }?.let { json.addProperty("parts", countsOf(it)) }
        return json
    }

    /**
     * How many of the [parts] of an item are open, fixed and closed, leaving out a group that counts none: with none
     * open the item itself can be set to fixed, and with all closed it can be closed.
     */
    private fun countsOf(parts: List<TodoItem>): String {
        val closed = parts.count { it.isClosed }
        val fixed = parts.count { it.status == Status.FIXED }
        return listOf(parts.size - fixed - closed to "open", fixed to "fixed", closed to "closed")
            .filter { it.first > 0 }.joinToString { "${it.first} ${it.second}" }
    }

    private fun rows(rows: List<JsonObject>): String =
        if (rows.isEmpty()) "[]" else rows.joinToString(",\n", "[\n", "\n]") { it.toString() }

    private fun words(value: String?): List<String> =
        value.orEmpty().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }

    /** Words, and phrases written in double quotes, separated by commas or spaces. */
    private fun phrases(value: String?): List<String> =
        Regex("\"([^\"]*)\"|[^\\s,\"]+").findAll(value.orEmpty())
            .map { it.groups[1]?.value?.trim() ?: it.value }.filter { it.isNotEmpty() }.toList()

    private fun TodoItem.says(words: String): Boolean =
        title.contains(words, ignoreCase = true) || details.contains(words, ignoreCase = true)

    /** A file or directory as anchors spell it: forward slashes, relative, no slash at the end. "" is the root. */
    private fun directory(path: String): String =
        path.trim().replace('\\', '/').removePrefix("./").trim('/').let { if (it == ".") "" else it }

    private fun isUnder(file: String, directory: String): Boolean =
        directory.isEmpty() || file == directory || file.startsWith("$directory/")

    /** A file as anchors spell it: forward slashes, relative to the project root. */
    private fun filePath(path: String): String = path.trim().replace('\\', '/').removePrefix("./")

    private fun item(id: String): TodoItem =
        readable().firstOrNull { it.id == id } ?: throw ToolError("there is no item with id $id")

    private fun readable(): List<TodoItem> {
        store.error?.let { throw ToolError("${store.dir.path} cannot be read: $it. Fix or delete project.json there.") }
        return store.items
    }

    /** Links that find an item of another project by its qualified id. */
    private fun links(items: List<TodoItem>) = Links(items) { workspace.store.find(it) }

    /**
     * [items] with their anchors as the code is now: the files are read from disk again, since an
     * agent changes them between calls. Every answer that says which anchors are lost goes through
     * here, so that two answers in a row agree.
     */
    private fun asNow(items: List<TodoItem>): List<TodoItem> {
        val now = store.items.associateBy { it.id }
        return items.map { AnchorsNow.of(now[it.id] ?: it, scope.checkout) }
    }

    private fun <T> storeCall(block: () -> T): T = try {
        block()
    } catch (e: StoreException) {
        throw ToolError(e.message ?: "the change could not be saved")
    }

    private fun parseIds(value: String): List<String> = try {
        ItemRef.parseList(value).map { it.toString() }
    } catch (e: IllegalArgumentException) {
        throw ToolError(e.message ?: "not an item id")
    }

    private fun parseId(value: String): String =
        ItemRef.normalize(value) ?: throw ToolError("\"${value.trim()}\" is not an item id: write T-1, T-2, ... or project/T-1")

    private fun parsePriority(value: String): Priority =
        Priority.fromJson(value) ?: throw ToolError("priority must be one of p1, p2, p3")

    private fun parseEffort(value: String): Effort =
        Effort.fromJson(value) ?: throw ToolError("effort must be one of s, m, l")

    private fun parseStatus(value: String): Status =
        Status.fromJson(value) ?: throw ToolError("status must be one of open, in_progress, fixed, done, wont_fix")

    private companion object {
        val PLACE_FIELDS = setOf("path", "startLine", "endLine", "places")
        val LIST_FIELDS = setOf("tags", "blockedBy", "places")
        val DECISION_FIELDS = setOf("question", "options", "answer")
        val CREATE_FIELDS = setOf("title", "details", "priority", "tags") + PLACE_FIELDS +
            setOf("blockedBy", "duplicateOf", "parent", "source", "toDecide", "effort", "ref")
        val UPDATE_FIELDS = setOf("id", "title", "details", "priority", "tags", "status") + PLACE_FIELDS + setOf(
            "blockedBy", "duplicateOf", "parent", "source", "fixedIn", "resolution", "toDecide", "effort", "comment",
        )
        val REF_NAME = Regex("[A-Za-z0-9_-]+")
        val REF = Regex("\\$(${REF_NAME.pattern})")
        val RANGE = Regex("(\\S+?)(\\.{2,3})(\\S+)")
    }
}

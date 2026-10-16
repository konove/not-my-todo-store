package io.github.konove.notmytodo.model

import com.google.gson.JsonElement

enum class Priority {
    P1, P2, P3;

    val json: String get() = name.lowercase()

    companion object {
        fun fromJson(s: String): Priority? = entries.firstOrNull { it.json == s.trim().lowercase() }
    }
}

/** A rough guess at how much work an item is: small, medium or large. */
enum class Effort {
    S, M, L;

    val json: String get() = name.lowercase()

    companion object {
        fun fromJson(s: String): Effort? = entries.firstOrNull { it.json == s.trim().lowercase() }
    }
}

enum class Status(val label: String) {
    OPEN("Open"), IN_PROGRESS("In progress"), FIXED("Fixed, review"), DONE("Done"), WONT_FIX("Won't fix");

    val json: String get() = name.lowercase()

    override fun toString(): String = label

    companion object {
        fun fromJson(s: String): Status? = entries.firstOrNull { it.json == s.trim().lowercase() }
    }
}

enum class Author {
    USER, AGENT;

    val json: String get() = name.lowercase()

    companion object {
        fun fromJson(s: String): Author? = entries.firstOrNull { it.json == s.trim().lowercase() }
    }
}

/**
 * A place in a file, or the whole file. Lines are 1-based and inclusive; [path] is project-relative
 * with forward slashes. An anchor on the whole file has no lines: both are [WHOLE_FILE] and [text] is empty.
 * [unknown] holds the JSON fields this version does not know, so that they are written back as they came.
 */
data class Anchor(
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val text: String,
    val before: List<String>,
    val after: List<String>,
    val lost: Boolean = false,
    val unknown: Map<String, JsonElement> = emptyMap(),
) {
    val isFile: Boolean get() = startLine == WHOLE_FILE

    /** The lines as they are shown: `12`, `12-15`, or nothing for a whole file. */
    fun linesText(dash: String = "-"): String = when {
        isFile -> ""
        startLine == endLine -> "$startLine"
        else -> "$startLine$dash$endLine"
    }

    /** The path with the lines after a colon: `src/A.kt:12-15`, or the bare path for a whole file. */
    val place: String get() = if (isFile) path else "$path:${linesText()}"

    companion object {
        const val WHOLE_FILE = 0

        fun file(path: String): Anchor = Anchor(path, WHOLE_FILE, WHOLE_FILE, "", emptyList(), emptyList())
    }
}

/** A note added to an item after it was made. [time] is written like [TodoItem.updated]. */
data class Comment(
    val author: Author,
    val time: String,
    val text: String,
    val unknown: Map<String, JsonElement> = emptyMap(),
)

/**
 * A question I was asked about an item and what I answered. [options] are the answers I was offered,
 * and [answer] is the one I chose or what I typed instead. [time] is written like [TodoItem.updated].
 */
data class Decision(
    val question: String,
    val options: List<String>,
    val answer: String,
    val time: String,
    val unknown: Map<String, JsonElement> = emptyMap(),
)

data class TodoItem(
    val id: String,
    val title: String,
    val details: String = "",
    val priority: Priority = Priority.P2,
    val tags: List<String> = emptyList(),
    val status: Status = Status.OPEN,
    val author: Author = Author.USER,
    val created: String = "",
    val updated: String = "",
    /** Oldest first. Only ever added to, so that a note never replaces what someone else wrote. */
    val comments: List<Comment> = emptyList(),
    /** Every place the item is attached to; none for a plain note. */
    val anchors: List<Anchor> = emptyList(),
    /** The ids of the items that must be closed before this one can be worked on. */
    val blockedBy: List<String> = emptyList(),
    /** The id of the item that already says what this one says. */
    val duplicateOf: String? = null,
    /** The id of the item this one is a part of. A parent has no parent of its own. */
    val parent: String? = null,
    /** Where the item came from: the commit, the range of commits or the run that left it behind. */
    val source: String? = null,
    /** The commit that fixed it. */
    val fixedIn: String? = null,
    /** What was done to fix it, or why it was closed without a change; Markdown, a sentence or two. */
    val resolution: String? = null,
    /** How much work it is thought to be; none when nobody has said. */
    val effort: Effort? = null,
    /** What I have to decide before this can be fixed; Markdown. It stays when I have decided. */
    val toDecide: String? = null,
    /** What I was asked and what I answered, oldest first. Only ever added to, like [comments]. */
    val decisions: List<Decision> = emptyList(),
    /** The JSON fields this version does not know, written back as they came. */
    val unknown: Map<String, JsonElement> = emptyMap(),
) {
    /** The first anchor: where the item is listed, sorted and grouped. */
    val anchor: Anchor? get() = anchors.firstOrNull()
    val anyLost: Boolean get() = anchors.any { it.lost }
    val number: Int get() = ItemId.number(id)
    val isClosed: Boolean get() = status == Status.DONE || status == Status.WONT_FIX

    /** Marked with the [Tags.NEEDS_DECISION] tag: an agent must not fix this until I have decided something. */
    val needsDecision: Boolean get() = Tags.NEEDS_DECISION in tags
}

data class TodoFile(
    val version: Int = TodoJson.NEWEST_VERSION,
    val nextId: Int = 1,
    val items: List<TodoItem> = emptyList(),
    val unknown: Map<String, JsonElement> = emptyMap(),
)

/** How [items], all those of one file, refer to each other. A link to an item that is not there counts for nothing. */
class Links(items: List<TodoItem>) {
    private val byId = items.associateBy { it.id }
    private val parts = items.filter { it.parent != null }.groupBy { it.parent }

    /** The items [item] still waits for: those it is blocked by that are not closed. */
    fun openBlockers(item: TodoItem): List<TodoItem> = item.blockedBy.mapNotNull { byId[it] }.filterNot { it.isClosed }

    fun isBlocked(item: TodoItem): Boolean = openBlockers(item).isNotEmpty()

    /** The items [id] is the parent of, in id order. */
    fun children(id: String): List<TodoItem> = parts[id].orEmpty()

    /** True while [item] has a part that is not closed: its priority is then that of its parts and not its own. */
    fun hasOpenParts(item: TodoItem): Boolean = children(item.id).any { !it.isClosed }

    /**
     * The priority [item] is shown, sorted and filtered with: the highest among its parts that are
     * not closed, whatever its own is, so that a part never stands under a parent of less weight.
     * An item with no open part has its own.
     */
    fun priority(item: TodoItem): Priority =
        children(item.id).filterNot { it.isClosed }.minOfOrNull { it.priority } ?: item.priority

    /** [item] with the priority it is shown with, for where it is only read. Not to be saved. */
    fun shown(item: TodoItem): TodoItem = priority(item).let { if (it == item.priority) item else item.copy(priority = it) }

    companion object {
        val NONE = Links(emptyList())
    }
}

object ItemId {
    private val pattern = Regex("T-([1-9][0-9]*)")

    /** An id as it may be typed: `T-7`, `t-7` or `7`. Null when it is none of those. */
    fun normalize(raw: String): String? =
        raw.trim().uppercase().let { if (it.toIntOrNull() != null) "T-$it" else it }.takeIf { parse(it) != null }

    /** The ids in [text], separated by commas or spaces. Throws [IllegalArgumentException] on anything that is not an id. */
    fun parseList(text: String): List<String> = text.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.map {
        normalize(it) ?: throw IllegalArgumentException("\"$it\" is not an item id: write T-1, T-2, ...")
    }.distinct()

    fun of(n: Int): String = "T-$n"

    fun parse(id: String): Int? = pattern.matchEntire(id)?.groupValues?.get(1)?.toIntOrNull()

    fun number(id: String): Int = parse(id) ?: throw IllegalArgumentException("not an item id: $id")
}

object Tags {
    const val NEEDS_DECISION = "needs-decision"

    fun normalize(raw: String): String? =
        raw.trim().removePrefix("#").lowercase().takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }

    fun normalizeAll(raw: List<String>): List<String> = raw.mapNotNull(::normalize).distinct()

    fun parseList(text: String): List<String> = normalizeAll(text.split(Regex("[\\s,]+")))
}

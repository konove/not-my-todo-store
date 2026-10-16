package io.github.konove.notmytodo.store

import io.github.konove.notmytodo.model.Anchor
import io.github.konove.notmytodo.model.Author
import io.github.konove.notmytodo.model.Comment
import io.github.konove.notmytodo.model.Decision
import io.github.konove.notmytodo.model.Effort
import io.github.konove.notmytodo.model.ItemId
import io.github.konove.notmytodo.model.ItemRef
import io.github.konove.notmytodo.model.Priority
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.Tags
import io.github.konove.notmytodo.model.TodoFile
import io.github.konove.notmytodo.model.TodoFormatException
import io.github.konove.notmytodo.model.TodoItem
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CopyOnWriteArrayList

data class Draft(
    val title: String,
    val details: String = "",
    val priority: Priority = Priority.P2,
    val tags: List<String> = emptyList(),
    val author: Author = Author.USER,
    val anchors: List<Anchor> = emptyList(),
    val blockedBy: List<String> = emptyList(),
    val duplicateOf: String? = null,
    val parent: String? = null,
    val source: String? = null,
    val toDecide: String? = null,
    val effort: Effort? = null,
)

/**
 * Holds the items of one project folder of the store. The files are the source of truth: they are
 * read again before every change, under the store's [lock], and only the items a change touched are
 * written. A change that is only kept in memory, such as the lines an anchor has moved to, is written
 * when its item is saved for another reason, so that a file does not change with every edit of the
 * code above an anchor. [foreign] finds an item of another project by its qualified id.
 */
class ItemStore(
    val dir: ProjectDir,
    private val lock: StoreLock,
    private val foreign: (String) -> TodoItem? = { null },
    private val clock: () -> Instant = Instant::now,
) {
    val key: String get() = dir.key
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var data = TodoFile()

    /** The items as the files have them: [data] without the changes that are only kept in memory. */
    private var saved = TodoFile()
    private var record: ProjectRecord? = null
    private var broken: String? = null
    private var bad: List<ProjectDir.Unreadable> = emptyList()
    private var stamp: String? = null

    /** Whether a project.json was ever read here: a project that had one and has none is gone, not new. */
    private var known = false

    init {
        synchronized(this) { load() }
    }

    val items: List<TodoItem>
        get() = synchronized(this) { if (broken != null) emptyList() else data.items }

    val error: String?
        get() = synchronized(this) { broken }

    /** The item files that could not be read. They are left as they are, and their ids are not given out again. */
    val unreadable: List<ProjectDir.Unreadable>
        get() = synchronized(this) { bad }

    /** What project.json says; null while the project has none. */
    val project: ProjectRecord?
        get() = synchronized(this) { record }

    /** The id the next new item gets. */
    val nextId: Int
        get() = synchronized(this) { data.nextId }

    fun find(id: String): TodoItem? = items.firstOrNull { it.id == id }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun reload(): Boolean {
        val changed = synchronized(this) { load() }
        if (changed) fire()
        return changed
    }

    fun create(draft: Draft): TodoItem = mutate { created(it, draft) }

    /**
     * Changes item [id]. Without [touch] its updated time stays. Without [persist] the change is
     * kept in memory and written when the item is next saved for another reason.
     */
    fun update(id: String, touch: Boolean = true, persist: Boolean = true, change: (TodoItem) -> TodoItem): TodoItem =
        mutate(persist) { updated(it, id, touch, change) }

    /** Makes several changes as one: they are written together or, when one of them fails, not at all. */
    fun <T> batch(changes: (Batch) -> T): T = mutate { f ->
        val batch = Batch(f)
        val result = changes(batch)
        batch.file to result
    }

    /** The changes of one [batch]. Each is made to what the ones before it left. */
    inner class Batch internal constructor(internal var file: TodoFile) {
        fun create(draft: Draft): TodoItem = created(file, draft).also { file = it.first }.second

        fun update(id: String, touch: Boolean = true, change: (TodoItem) -> TodoItem): TodoItem =
            updated(file, id, touch, change).also { file = it.first }.second

        fun comment(id: String, author: Author, text: String): TodoItem = update(id, change = commented(author, text))

        /** Adds [item] as it is, with its id: for items brought from elsewhere, whose links are already right. */
        fun put(item: TodoItem) {
            if (file.items.any { it.id == item.id }) throw StoreException("there already is an item with id ${item.id}")
            if (bad.any { it.file == "${item.id}.json" }) throw StoreException("${item.id}.json cannot be read and is left as it is")
            file = file.copy(nextId = maxOf(file.nextId, item.number + 1), items = (file.items + item).sortedBy { it.number })
        }

        /**
         * Puts [item] in place of the item with its id exactly as given: its links are not checked,
         * nothing is normalised and its updated time stays. For rewriting links that only name an
         * item differently, where a link that was already dangling must stay as it was.
         */
        fun replace(item: TodoItem) {
            if (file.items.none { it.id == item.id }) throw StoreException("there is no item with id ${item.id}")
            file = file.copy(items = file.items.map { if (it.id == item.id) item else it })
        }

        /** Takes item [id] out and leaves the links to it as they are: for items taken elsewhere. */
        fun remove(id: String): TodoItem {
            val gone = file.items.firstOrNull { it.id == id } ?: throw StoreException("there is no item with id $id")
            file = file.copy(items = file.items - gone)
            return gone
        }

        /** Makes sure no new item gets an id below [nextId]. */
        fun reserve(nextId: Int) {
            file = file.copy(nextId = maxOf(file.nextId, nextId))
        }
    }

    /** A link as this project stores it: bare for one of its own items, `key/T-n` for one of another. */
    private fun local(ref: String): String = ItemRef.parse(ref)?.storedFrom(key) ?: ref

    private fun created(f: TodoFile, draft: Draft): Pair<TodoFile, TodoItem> {
        val title = draft.title.trim()
        if (title.isEmpty()) throw StoreException("the title must not be empty")
        val now = timestamp()
        val item = TodoItem(
            id = ItemId.of(f.nextId), title = title, details = draft.details, priority = draft.priority,
            tags = Tags.normalizeAll(draft.tags), status = Status.OPEN, author = draft.author,
            created = now, updated = now, anchors = draft.anchors,
            blockedBy = draft.blockedBy.map(::local).distinct(), duplicateOf = draft.duplicateOf?.let(::local),
            parent = draft.parent?.let(::local),
            source = draft.source.said(), toDecide = draft.toDecide.said(), effort = draft.effort,
        )
        checkLinks(item, f.items + item)
        return f.copy(nextId = f.nextId + 1, items = f.items + item) to item
    }

    private fun updated(f: TodoFile, id: String, touch: Boolean, change: (TodoItem) -> TodoItem): Pair<TodoFile, TodoItem> {
        val old = f.items.firstOrNull { it.id == id } ?: throw StoreException("there is no item with id $id")
        val changed = change(old)
        val edited = changed.copy(
            id = old.id, author = old.author, created = old.created, updated = old.updated,
            title = changed.title.trim(), tags = Tags.normalizeAll(changed.tags),
            blockedBy = changed.blockedBy.map(::local).distinct(), duplicateOf = changed.duplicateOf?.let(::local),
            parent = changed.parent?.let(::local),
            source = changed.source.said(), fixedIn = changed.fixedIn.said(), resolution = changed.resolution.said(),
            toDecide = changed.toDecide.said(),
            // A change may build the item or an anchor anew; what a newer version wrote stays either way.
            // An anchor built anew has no unknown fields of its own and takes those of the one it replaces.
            unknown = old.unknown,
            anchors = changed.anchors.mapIndexed { i, a ->
                val replaced = old.anchors.getOrNull(i)?.takeIf { it !in changed.anchors }
                if (a.unknown.isEmpty() && replaced != null) a.copy(unknown = replaced.unknown) else a
            },
        )
        if (edited.title.isEmpty()) throw StoreException("the title must not be empty")
        if (edited == old) return f to old
        // Links that were already there are left alone, so an item with a bad one can still be edited.
        if (edited.blockedBy != old.blockedBy || edited.duplicateOf != old.duplicateOf || edited.parent != old.parent) {
            checkLinks(edited, f.items.map { if (it.id == id) edited else it })
        }
        val saved = if (touch) edited.copy(updated = timestamp()) else edited
        return f.copy(items = f.items.map { if (it.id == id) saved else it }) to saved
    }

    /**
     * The links of [item], one of [items], must lead to other items that are there and must not come
     * back to it. A link to another project must find its item there; a circle through another
     * project is not looked for. Parts go one level deep and stay in their parent's project.
     */
    private fun checkLinks(item: TodoItem, items: List<TodoItem>) {
        val byId = items.associateBy { it.id }
        fun check(id: String, what: String) {
            if (id == item.id) throw StoreException("${item.id} cannot be $what itself")
            val there = if ('/' in id) foreign(id) != null else id in byId
            if (!there) throw StoreException("there is no item with id $id for ${item.id} to be $what")
        }
        fun circles(first: String, next: (TodoItem) -> List<String>): Boolean {
            val seen = HashSet<String>()
            val left = ArrayDeque(listOf(first))
            while (left.isNotEmpty()) {
                val id = left.removeFirst()
                if (id == item.id) return true
                if (seen.add(id)) byId[id]?.let { left += next(it) }
            }
            return false
        }
        for (id in item.blockedBy) {
            check(id, "blocked by")
            if (circles(id) { it.blockedBy }) throw StoreException("${item.id} cannot be blocked by $id: $id waits for ${item.id}")
        }
        item.duplicateOf?.let { id ->
            check(id, "a duplicate of")
            if (circles(id) { listOfNotNull(it.duplicateOf) }) {
                throw StoreException("${item.id} cannot be a duplicate of $id: $id is a duplicate of ${item.id}")
            }
        }
        item.parent?.let { id ->
            if ('/' in id) throw StoreException("${item.id} cannot be a part of $id: a part must be in the same project as its parent")
            check(id, "a part of")
            byId.getValue(id).parent?.let {
                throw StoreException("${item.id} cannot be a part of $id: $id is itself a part of $it, and parts go one level deep")
            }
            if (items.any { it.parent == item.id }) {
                throw StoreException("${item.id} cannot be a part of $id: it has parts of its own, and parts go one level deep")
            }
        }
    }

    /** Adds a note to the end of the item's comments. Nothing else of the item is read from the caller. */
    fun comment(id: String, author: Author, text: String): TodoItem = update(id, change = commented(author, text))

    private fun commented(author: Author, text: String): (TodoItem) -> TodoItem {
        val note = text.trim()
        if (note.isEmpty()) throw StoreException("the comment text must not be empty")
        return { it.copy(comments = it.comments + Comment(author, timestamp(), note)) }
    }

    /**
     * Adds what I was asked and what I answered to the end of the item's decisions, with the time.
     * The item no longer waits for me: its [Tags.NEEDS_DECISION] tag goes, and what was to be decided stays.
     */
    fun decide(id: String, decisions: List<Decision>): TodoItem {
        if (decisions.isEmpty()) throw StoreException("there must be at least one decision")
        val now = timestamp()
        val added = decisions.map { d ->
            val question = d.question.trim()
            val answer = d.answer.trim()
            if (question.isEmpty()) throw StoreException("the question must not be empty")
            if (answer.isEmpty()) throw StoreException("the answer must not be empty")
            Decision(question, d.options.map { it.trim() }.filter { it.isNotEmpty() }, answer, now)
        }
        return update(id) { it.copy(decisions = it.decisions + added, tags = it.tags - Tags.NEEDS_DECISION) }
    }

    /**
     * Deletes item [id] and returns it. [check] is given the item and all the items there are, and
     * throws a [StoreException] to turn the delete down.
     */
    fun delete(id: String, check: (TodoItem, List<TodoItem>) -> Unit = { _, _ -> }): TodoItem =
        mutate { f ->
            val gone = f.items.firstOrNull { it.id == id } ?: throw StoreException("there is no item with id $id")
            check(gone, f.items)
            // Nothing is left pointing at an item that is gone.
            val left = f.items.filterNot { it.id == id }.map {
                if (id !in it.blockedBy && it.duplicateOf != id && it.parent != id) it
                else it.copy(
                    blockedBy = it.blockedBy - id,
                    duplicateOf = it.duplicateOf.takeIf { d -> d != id },
                    parent = it.parent.takeIf { p -> p != id },
                )
            }
            f.copy(items = left) to gone
        }

    private fun <T> mutate(persist: Boolean = true, block: (TodoFile) -> Pair<TodoFile, T>): T {
        var changed = false
        try {
            return lock.withLock {
                synchronized(this) {
                    changed = load()
                    broken?.let { throw StoreException("${dir.path} cannot be read: $it") }
                    if (known && record == null) throw StoreException("$key is gone: it was renamed or deleted")
                    record?.takeIf { it.version > ProjectJson.VERSION }?.let {
                        throw StoreException(
                            "${dir.path} was written by a newer version of not-my-todo-store (layout ${it.version}): it can be read but not changed"
                        )
                    }
                    val (next, result) = block(data)
                    if (next != data) {
                        if (persist) {
                            // Only what this change touched is written as it is in memory.
                            val before = data.items.associateBy { it.id }
                            val onDisk = saved.items.associateBy { it.id }
                            val out = next.copy(items = next.items.map { if (before[it.id] == it) onDisk[it.id] ?: it else it })
                            write(out)
                            saved = out
                        }
                        data = next
                        changed = true
                    }
                    result
                }
            }
        } finally {
            if (changed) fire()
        }
    }

    /** Reads the files if any of them changed since they were last read or written. Returns true if state changed. */
    private fun load(): Boolean {
        val now = try {
            dir.stamp()
        } catch (e: IOException) {
            return fail("cannot be read: ${e.message}")
        }
        if (now == stamp) return false
        stamp = now
        val snapshot = try {
            dir.read()
        } catch (e: TodoFormatException) {
            return fail(e.message ?: "cannot be read")
        } catch (e: IOException) {
            return fail("cannot be read: ${e.message}")
        }
        val before = Triple(data, bad, broken)
        // An unreadable file still holds its id: a new item must not take it.
        val numbers = snapshot.items.map { it.number } + snapshot.unreadable.mapNotNull { ItemId.parse(it.file.removeSuffix(".json")) }
        val read = TodoFile(nextId = maxOf(snapshot.record?.nextId ?: 1, (numbers.maxOrNull() ?: 0) + 1), items = snapshot.items)
        // An item the files did not change keeps what is only in memory.
        val was = saved.items.associateBy { it.id }
        val live = data.items.associateBy { it.id }
        data = read.copy(items = read.items.map { if (was[it.id] == it) live[it.id] ?: it else it })
        saved = read
        record = snapshot.record
        if (record != null) known = true
        bad = snapshot.unreadable
        broken = null
        return Triple(data, bad, broken) != before
    }

    private fun fail(message: String): Boolean {
        val changed = broken != message
        broken = message
        data = TodoFile()
        saved = TodoFile()
        record = null
        bad = emptyList()
        return changed
    }

    private fun write(next: TodoFile) {
        val now = (record ?: ProjectRecord(key)).copy(nextId = next.nextId)
        dir.write(now.takeIf { it != record }, saved.items, next.items)
        record = now
        stamp = dir.stamp()
    }

    /** A text with something in it, trimmed; a blank one is as good as none. */
    private fun String?.said(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun timestamp(): String = clock().truncatedTo(ChronoUnit.SECONDS).toString()

    /** A listener that throws must not hide a change that is already on disk, or starve the others. */
    private fun fire() {
        for (listener in listeners) {
            try {
                listener()
            } catch (e: Exception) {
                System.getLogger(ItemStore::class.java.name).log(System.Logger.Level.WARNING, "TODO store listener failed", e)
            }
        }
    }
}

package io.github.konove.notmytodo.store

import io.github.konove.notmytodo.model.ItemId
import io.github.konove.notmytodo.model.ItemRef
import io.github.konove.notmytodo.model.TodoFormatException
import io.github.konove.notmytodo.model.TodoItem
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Where [folder] belongs: the root of its repository, or the folder itself outside git, its remotes,
 * and its project when it has one. [root] is null for a folder that is never a project.
 */
data class Resolution(val folder: Path, val root: Path?, val remotes: List<String>, val record: ProjectRecord?)

/** What a move did: the old id of each item moved and its new one, and how many anchors were dropped. */
data class Moved(val ids: Map<String, String>, val anchorsDropped: Int)

/**
 * The store of one machine: a folder of projects, each with its items. Every write goes through
 * [lock], shared by every process that writes. [home] and the file system root are never a project.
 */
class Store(
    val root: Path,
    val git: Git = Git(),
    private val home: Path = Path.of(System.getProperty("user.home")),
    private val clock: () -> Instant = Instant::now,
) {
    val lock = StoreLock(root.resolve("store.lock"))
    private val projectsDir: Path get() = root.resolve("projects")
    private val open = ConcurrentHashMap<String, ItemStore>()

    data class Ensured(val record: ProjectRecord, val created: Boolean)

    fun dir(key: String): ProjectDir {
        if (!ItemRef.KEY.matches(key)) throw StoreException("\"$key\" is not a project key")
        return ProjectDir(projectsDir.resolve(key))
    }

    /** Every project whose project.json can be read, by key. */
    fun projects(): List<ProjectRecord> {
        if (!Files.isDirectory(projectsDir)) return emptyList()
        val dirs = Files.list(projectsDir).use { s -> s.filter { Files.isDirectory(it) }.sorted().toList() }
        return dirs.mapNotNull {
            try {
                ProjectDir(it).readRecord()
            } catch (_: TodoFormatException) {
                null
            } catch (_: IOException) {
                null
            }
        }
    }

    fun project(key: String): ProjectRecord? = try {
        if (ItemRef.KEY.matches(key)) dir(key).readRecord() else null
    } catch (_: TodoFormatException) {
        null
    }

    /** The items of project [key], read again from its files. */
    fun items(key: String): ItemStore =
        open.computeIfAbsent(key) { ItemStore(dir(it), lock, ::find, clock) }.also { it.reload() }

    /** The item a qualified id such as `infra/T-3` names; null for a bare id or one that is not there. */
    fun find(ref: String): TodoItem? {
        val parsed = ItemRef.parse(ref) ?: return null
        val key = parsed.project ?: return null
        if (project(key) == null) return null
        return items(key).find(parsed.id)
    }

    fun resolve(folder: Path): Resolution {
        val abs = folder.toAbsolutePath().normalize()
        val repo = git.root(abs)
        val candidate = repo ?: abs
        val root = candidate.takeUnless { real(it) == real(home.toAbsolutePath().normalize()) || it.parent == null }
        val remotes = repo?.let(git::remotes).orEmpty()
        if (root == null) return Resolution(abs, null, remotes, null)
        val records = projects()
        val known = records.firstOrNull { root.toString() in it.checkouts }
            ?: records.firstOrNull { r -> remotes.isNotEmpty() && r.remotes.any { it in remotes } }
        return Resolution(abs, root, remotes, known?.let { tidy(it, root) })
    }

    /**
     * The project of [resolution], made now when it has none; inbox for a folder that is never a
     * project. Another process may have made it since [resolution] was found, so it is looked for
     * again under the lock.
     */
    fun ensure(resolution: Resolution): Ensured = lock.withLock {
        val root = resolution.root ?: return@withLock Ensured(inbox(), false)
        val again = resolve(resolution.folder)
        again.record?.let { return@withLock Ensured(it, false) }
        val key = keyFor(root, folderNames())
        val record = ProjectRecord(key, again.remotes, listOf(root.toString()))
        dir(key).writeRecord(record)
        Ensured(record, true)
    }

    fun inbox(): ProjectRecord = readable(INBOX) ?: lock.withLock {
        readable(INBOX) ?: ProjectRecord(INBOX).also { dir(INBOX).writeRecord(it) }
    }

    /** The record of [key]; null when it has no project.json yet, an error when it has one that cannot be read. */
    private fun readable(key: String): ProjectRecord? {
        val dir = dir(key)
        return try {
            dir.readRecord()
        } catch (e: TodoFormatException) {
            throw unreadable(dir, e.message)
        } catch (e: IOException) {
            throw unreadable(dir, e.message)
        }
    }

    private fun unreadable(dir: ProjectDir, why: String?) =
        StoreException("${dir.path} cannot be read ($why); it is left as it is")

    /** Every folder under projects, readable or not: a key is taken as long as its folder is there. */
    private fun folderNames(): Set<String> {
        if (!Files.isDirectory(projectsDir)) return emptySet()
        return Files.list(projectsDir).use { s -> s.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.toList() }.toSet()
    }

    private fun real(p: Path): Path = runCatching { p.toRealPath() }.getOrDefault(p)

    /** Changes project.json of [key] under the lock. */
    fun updateRecord(key: String, change: (ProjectRecord) -> ProjectRecord): ProjectRecord = lock.withLock {
        val now = dir(key).readRecord() ?: throw StoreException("there is no project $key")
        if (now.version > ProjectJson.VERSION) {
            throw StoreException(
                "${dir(key).path} was written by a newer version of not-my-todo-store (layout ${now.version}): it can be read but not changed"
            )
        }
        change(now).also { if (it != now) dir(key).writeRecord(it) }
    }

    /**
     * [record] with [root] among its checkouts and the checkouts gone from this machine dropped. The
     * lock is taken only when something has to change, so a lookup that changes nothing never waits.
     */
    private fun tidy(record: ProjectRecord, root: Path): ProjectRecord {
        if (record.version > ProjectJson.VERSION) return record
        fun changed(r: ProjectRecord) = r.copy(
            checkouts = (r.checkouts + root.toString()).distinct().filter { it == root.toString() || Files.isDirectory(Path.of(it)) },
        )
        if (changed(record) == record) return record
        return updateRecord(record.key, ::changed)
    }

    /**
     * Moves item [id] of project [from] to project [to], with its parts when [withParts]. The items
     * get the next ids there and keep where they came from in movedFrom. Every link to them, in any
     * project, is rewritten. Their anchors are dropped: they were relative to the other project's checkout.
     */
    fun move(from: String, id: String, to: String, withParts: Boolean = false): Moved = lock.withLock {
        if (from == to) throw StoreException("$from/$id is already in $to")
        if (project(to) == null) throw StoreException("there is no project $to")
        val source = items(from)
        val item = source.find(id) ?: throw StoreException("there is no item with id $from/$id")
        item.parent?.let {
            throw StoreException("$from/$id is a part of $it: move $it with withParts, or take $id out of it first (parent \"\")")
        }
        val parts = source.items.filter { it.parent == id }
        if (parts.isNotEmpty() && !withParts) {
            throw StoreException("$from/$id has parts (${parts.joinToString(", ") { it.id }}): pass withParts to move them with it")
        }
        val moving = listOf(item) + parts
        val target = items(to)
        val ids = moving.mapIndexed { i, it -> it.id to ItemId.of(target.nextId + i) }.toMap()

        /** [ref], written on an item of project [was], as it is written on an item of project [now] after the move. */
        fun rewrite(ref: String, was: String, now: String): String {
            val r = ItemRef.parse(ref) ?: return ref
            val key = r.project ?: was
            val moved = if (key == from && r.id in ids) ItemRef(to, ids.getValue(r.id)) else ItemRef(key, r.id)
            return moved.storedFrom(now)
        }
        fun relinked(i: TodoItem, was: String, now: String) = i.copy(
            blockedBy = i.blockedBy.map { rewrite(it, was, now) },
            duplicateOf = i.duplicateOf?.let { rewrite(it, was, now) },
            parent = i.parent?.let { rewrite(it, was, now) },
        )
        fun relink(key: String, skip: Set<String> = emptySet()) {
            val store = items(key)
            val changes = store.items.filter { it.id !in skip }.mapNotNull { i -> relinked(i, key, key).takeIf { it != i } }
            if (changes.isNotEmpty()) store.batch { b -> changes.forEach { c -> b.update(c.id, touch = false) { c } } }
        }

        val brought = moving.map {
            relinked(it, from, to).copy(id = ids.getValue(it.id), anchors = emptyList(), movedFrom = "$from/${it.id}")
        }
        // The new items are written first, so that every rewritten link leads to an item that is there.
        target.batch { b -> brought.forEach(b::put) }
        relink(to, brought.map { it.id }.toSet())
        source.batch { b ->
            source.items.filter { it.id !in ids }.forEach { i ->
                relinked(i, from, from).takeIf { it != i }?.let { c -> b.update(i.id, touch = false) { c } }
            }
            ids.keys.forEach { b.remove(it) }
        }
        projects().map { it.key }.filter { it != from && it != to }.forEach { relink(it) }
        Moved(ids, moving.sumOf { it.anchors.size })
    }

    /** Renames project [old] to [new], and every qualified id that names it in every project. */
    fun rename(old: String, new: String): ProjectRecord = lock.withLock {
        val key = new.trim().lowercase()
        if (old == INBOX) throw StoreException("inbox cannot be renamed")
        if (!ItemRef.KEY.matches(key) || key == INBOX) {
            throw StoreException("\"$new\" cannot be a project key: use lower-case letters, digits, '.', '-' and '_'")
        }
        val record = project(old) ?: throw StoreException("there is no project $old")
        if (record.version > ProjectJson.VERSION) {
            throw StoreException("$old was written by a newer version of not-my-todo-store (layout ${record.version}): it can be read but not changed")
        }
        if (key == old) return@withLock record
        if (Files.exists(dir(key).path)) throw StoreException("there already is a project $key")
        try {
            Files.move(dir(old).path, dir(key).path)
        } catch (e: IOException) {
            throw StoreException("$old cannot be renamed: ${e.message}")
        }
        open.remove(old)
        val renamed = record.copy(key = key)
        dir(key).writeRecord(renamed)
        fun renamedRef(ref: String): String {
            val r = ItemRef.parse(ref) ?: return ref
            return if (r.project == old) ItemRef(key, r.id).toString() else ref
        }
        for (other in projects().map { it.key }) {
            val store = items(other)
            val changes = store.items.mapNotNull { i ->
                i.copy(blockedBy = i.blockedBy.map(::renamedRef), duplicateOf = i.duplicateOf?.let(::renamedRef)).takeIf { it != i }
            }
            if (changes.isNotEmpty()) store.batch { b -> changes.forEach { c -> b.update(c.id, touch = false) { c } } }
        }
        renamed
    }

    companion object {
        const val INBOX = "inbox"

        /** A key for the project of [root]: its folder's name, made safe, with -2, -3, ... when that is [taken]. */
        fun keyFor(root: Path, taken: Set<String>): String {
            val base = (root.fileName?.toString() ?: "").lowercase()
                .replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.', '_').ifEmpty { "project" }
            return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { it != INBOX && it !in taken }
        }
    }
}

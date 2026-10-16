package io.github.konove.notmytodo.store

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

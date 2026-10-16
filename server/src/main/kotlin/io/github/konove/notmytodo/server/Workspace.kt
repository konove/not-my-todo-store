package io.github.konove.notmytodo.server

import io.github.konove.notmytodo.model.ItemRef
import io.github.konove.notmytodo.model.TodoFormatException
import io.github.konove.notmytodo.store.FixCommits
import io.github.konove.notmytodo.store.ItemStore
import io.github.konove.notmytodo.store.ProjectRecord
import io.github.konove.notmytodo.store.Resolution
import io.github.konove.notmytodo.store.Store
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The items of one project as a tool call sees them. [checkout] is the folder anchors are relative
 * to; null when this machine has none. [notice] says the project was made by this call.
 */
class Scope(val key: String, val items: ItemStore, val checkout: Path?, val fixes: FixCommits?, val notice: String? = null)

/** The store, and the folder Claude Code started the server in. */
class Workspace(val store: Store, val cwd: Path) {
    private val fixes = ConcurrentHashMap<Path, FixCommits>()

    /** The project of [cwd], found again for every call: another session may have made it meanwhile. */
    fun here(): Resolution = store.resolve(cwd)

    /**
     * The project a tool works in: [project] when given (a key, inbox or a folder), else the one of
     * [cwd]. Null when that folder has no project yet, unless [create], which makes it.
     */
    fun scope(project: String?, create: Boolean = false): Scope? {
        val wanted = project?.trim()?.takeIf { it.isNotEmpty() }
        val resolution = when {
            wanted == null -> here()
            wanted == Store.INBOX -> return scopeOf(store.inbox())
            isPath(wanted) -> {
                // A typo must not make a project for a folder that is not there.
                val folder = cwd.resolve(expand(wanted)).normalize()
                if (!Files.isDirectory(folder)) throw ToolError("no such folder: $folder")
                store.resolve(folder)
            }
            else -> return scopeOf(store.project(wanted.lowercase()) ?: throw ToolError(unknown(wanted)))
        }
        resolution.record?.let { return scopeOf(it, resolution) }
        // The home folder and / file into inbox, so what was filed there is found there again.
        if (resolution.root == null && (create || Files.isDirectory(store.dir(Store.INBOX).path))) return scopeOf(store.inbox())
        unreadable(resolution)
        if (!create) return null
        val ensured = store.ensure(resolution)
        val notice = if (ensured.created) "created project \"${ensured.record.key}\" for ${resolution.root}" else null
        return scopeOf(ensured.record, resolution, notice)
    }

    fun scopeOf(record: ProjectRecord): Scope = scopeOf(record, null)

    /** The scope and bare id of the item [raw] names: a qualified id names its own project. */
    fun target(raw: String, project: String?): Pair<Scope, String> {
        val ref = ItemRef.parse(raw) ?: throw ToolError("\"${raw.trim()}\" is not an item id: write T-1, T-2, ... or project/T-1")
        val scope = scope(ref.project ?: project) ?: throw ToolError("there is no item with id ${ref.id}: $cwd has no project yet")
        return scope to ref.id
    }

    private fun scopeOf(record: ProjectRecord, resolution: Resolution?, notice: String? = null): Scope {
        val here = (resolution ?: here()).takeIf { it.record?.key == record.key }?.root
        val checkout = here ?: record.checkouts.map { Path.of(it) }.firstOrNull { Files.isDirectory(it) }
        return Scope(record.key, store.items(record.key), checkout, checkout?.let { c -> fixes.computeIfAbsent(c) { FixCommits(it, store.git) } }, notice)
    }

    /**
     * Refuses a folder that has no project while the project.json of some project cannot be read:
     * that one may be the folder's, and another made now would split its items in two.
     */
    private fun unreadable(resolution: Resolution) {
        val projects = store.root.resolve("projects")
        if (!Files.isDirectory(projects)) return
        val keys = Files.list(projects).use { s -> s.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.sorted().toList() }
        for (key in keys) {
            val why = problem(key) ?: continue
            throw ToolError(
                "${store.dir(key).path.resolve("project.json")} cannot be read ($why), and it may be the project of " +
                    "${resolution.root}. Fix or delete project.json there."
            )
        }
    }

    /** Why the project.json of [key] cannot be read; null when it can, or when there is none. */
    private fun problem(key: String): String? {
        if (!ItemRef.KEY.matches(key)) return null
        return try {
            store.dir(key).readRecord()
            null
        } catch (e: TodoFormatException) {
            e.message ?: "it is not valid"
        } catch (e: IOException) {
            e.message ?: "it cannot be read"
        }
    }

    private fun isPath(text: String) = '/' in text || '\\' in text || text.startsWith(".") || text.startsWith("~")

    private fun expand(text: String): String =
        if (text == "~" || text.startsWith("~/")) System.getProperty("user.home") + text.removePrefix("~") else text

    private fun unknown(key: String): String {
        problem(key.lowercase())?.let {
            return "${store.dir(key.lowercase()).path.resolve("project.json")} cannot be read ($it). Fix or delete project.json there."
        }
        val keys = store.projects().map { it.key }
        return "there is no project \"$key\"" + if (keys.isEmpty()) "" else ": the projects are ${keys.joinToString(", ")} (see todo_projects)"
    }
}

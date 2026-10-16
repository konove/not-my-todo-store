package io.github.konove.notmytodo.server

import io.github.konove.notmytodo.model.ItemRef
import io.github.konove.notmytodo.store.FixCommits
import io.github.konove.notmytodo.store.ItemStore
import io.github.konove.notmytodo.store.ProjectRecord
import io.github.konove.notmytodo.store.Resolution
import io.github.konove.notmytodo.store.Store
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
     * [cwd]. Null when that folder has no project yet, unless [create], which makes it. With [inbox],
     * the home folder and / give inbox, where an item made there was filed.
     */
    fun scope(project: String?, create: Boolean = false, inbox: Boolean = false): Scope? {
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
        if (inbox && resolution.root == null && Files.isDirectory(store.dir(Store.INBOX).path)) return scopeOf(store.inbox())
        resolution.root?.let {
            unreadable(it)
            notImported(it)
        }
        if (!create) return null
        val ensured = store.ensure(resolution)
        val notice = if (ensured.created) "created project \"${ensured.record.key}\" for ${resolution.root}" else null
        return scopeOf(ensured.record, resolution, notice)
    }

    fun scopeOf(record: ProjectRecord): Scope = scopeOf(record, null)

    /** The project an item is moved to: as [scope], and a folder that is never a project, such as the home folder, means inbox. */
    fun destination(to: String): Scope? {
        scope(to, create = false, inbox = true)?.let { return it }
        val wanted = to.trim().takeIf { isPath(it) } ?: return null
        val folder = cwd.resolve(expand(wanted)).normalize()
        return if (Files.isDirectory(folder) && store.resolve(folder).root == null) scopeOf(store.inbox()) else null
    }

    /** The scope and bare id of the item [raw] names: a qualified id names its own project. */
    fun target(raw: String, project: String?): Pair<Scope, String> {
        val ref = ItemRef.parse(raw) ?: throw ToolError("\"${raw.trim()}\" is not an item id: write T-1, T-2, ... or project/T-1")
        val scope = scope(ref.project ?: project, create = false, inbox = true) ?: throw ToolError("there is no item with id ${ref.id}: $cwd has no project yet")
        return scope to ref.id
    }

    private fun scopeOf(record: ProjectRecord, resolution: Resolution?, notice: String? = null): Scope {
        val here = (resolution ?: here()).takeIf { it.record?.key == record.key }?.root
        val checkout = here ?: record.checkouts.map { Path.of(it) }.firstOrNull { Files.isDirectory(it) }
        return Scope(record.key, store.items(record.key), checkout, checkout?.let { c -> fixes.computeIfAbsent(c) { FixCommits(it, store.git) } }, notice)
    }

    /**
     * Refuses [root], which has no project, while the project its name would be the key of has a
     * project.json that cannot be read: that one may be its own, and another made now would split its items in two.
     */
    private fun unreadable(root: Path) {
        val key = Store.keyFor(root, emptySet())
        val why = store.problem(key) ?: return
        throw ToolError(
            "${store.dir(key).path.resolve("project.json")} cannot be read ($why), and it may be the project of $root. " +
                "Fix or delete project.json there."
        )
    }

    /**
     * The items.json the Not My TODO plugin keeps in [root], which has no project, while no project
     * has imported it; null when there is none. A project made now would hand out the ids that file
     * already has, and its import would then clash with every one of them.
     */
    fun unimported(root: Path): Path? {
        val file = root.resolve(".todos").resolve("items.json").toAbsolutePath().normalize()
        if (!Files.isRegularFile(file)) return null
        return file.takeIf { f -> store.projects().none { it.importedFrom?.path == f.toString() } }
    }

    /** Refuses [root], which has no project, while its items are still in the plugin's items.json. */
    private fun notImported(root: Path) {
        if (unimported(root) == null) return
        throw ToolError(
            "the items of $root are still in .todos/items.json: " +
                "open the project once in the IDE with Not My TODO 0.3.0 or newer, which moves them into the store"
        )
    }

    private fun isPath(text: String) = '/' in text || '\\' in text || text.startsWith(".") || text.startsWith("~")

    private fun expand(text: String): String =
        if (text == "~" || text.startsWith("~/")) System.getProperty("user.home") + text.removePrefix("~") else text

    private fun unknown(key: String): String {
        store.problem(key.lowercase())?.let {
            return "${store.dir(key.lowercase()).path.resolve("project.json")} cannot be read ($it). Fix or delete project.json there."
        }
        val keys = store.projects().map { it.key }
        return "there is no project \"$key\"" + if (keys.isEmpty()) "" else ": the projects are ${keys.joinToString(", ")} (see todo_projects)"
    }
}

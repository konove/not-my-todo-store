package io.github.konove.notmytodo.store

import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.TodoItem
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The commit that fixed an item which does not name one itself: a commit of the checkout at [root] whose
 * message names the item's id, as [parse] picks it. It is read from git and never written to the store, so
 * closing an item needs no change after the commit that fixed it. A fixedIn stored on the item always wins.
 */
class FixCommits(private val root: Path, private val git: Git) {
    /** What git was last asked for, and the state of its log of HEAD at that time. */
    private class Read(val commits: Map<String, String>, val stamp: String?, val time: Long)

    @Volatile
    private var read: Read? = null

    /** True for an item that is closed as fixed and does not say in which commit. */
    fun derives(item: TodoItem): Boolean =
        item.fixedIn == null && (item.status == Status.FIXED || item.status == Status.DONE)

    /** [item] with the commit that fixed it filled in, as far as it is known without asking git again. */
    fun shown(item: TodoItem): TodoItem {
        if (!derives(item)) return item
        return read?.commits?.get(item.id)?.let { item.copy(fixedIn = it) } ?: item
    }

    /** Asks git again when there were commits since it was last asked, and returns true when the answer is another one. */
    fun refresh(): Boolean {
        val stamp = stamp()
        val last = read
        val now = System.currentTimeMillis()
        // Without a stamp there is no telling whether anything was committed, so git is asked, but not all the time.
        val fresh = last != null && if (stamp != null) stamp == last.stamp else last.stamp == null && now - last.time < UNSTAMPED_MS
        if (fresh) return false
        val commits = git.run(root, "log", "--grep=T-[0-9]", "--format=%h$FIELD%B$RECORD")?.let(::parse).orEmpty()
        read = Read(commits, stamp, now)
        return commits != last?.commits.orEmpty()
    }

    /** The id of the commit that [name] stands for now, when it is a name such as HEAD and not an id already. */
    fun resolve(name: String): String? {
        val ref = name.trim()
        if (ref.isEmpty() || ref.startsWith("-") || ID_LIKE.matches(ref)) return null
        return git.run(root, "rev-parse", "--verify", "--quiet", "--short", "$ref^{commit}")?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Changes with every commit, checkout and reset. Null when there is no such log to look at. */
    private fun stamp(): String? = try {
        val dot = root.resolve(".git")
        // In a linked worktree .git is a file that names the directory.
        val dir = if (Files.isRegularFile(dot)) {
            Files.readString(dot).trim().removePrefix("gitdir:").trim().let { root.resolve(it).normalize() }
        } else dot
        val log = dir.resolve("logs").resolve("HEAD")
        "${Files.getLastModifiedTime(log).toMillis()}:${Files.size(log)}"
    } catch (_: IOException) {
        null
    }

    companion object {
        private const val UNSTAMPED_MS = 5000
        private const val FIELD = "%x1f"
        private const val RECORD = "%x1e"
        private val ID_LIKE = Regex("[0-9a-fA-F]{7,40}")
        /** An id of this project: infra/T-12 names an item of another one. */
        private val ID = Regex("(?<![\\w/])T-[1-9][0-9]*\\b")

        /**
         * The commit of each item id named in [log], which is the output of `git log`, newest commit first: the newest
         * one that names it in its subject, as "Fix T-12: ..." does, or else the oldest one that names it in its body,
         * since a later one only refers back to it.
         */
        fun parse(log: String): Map<String, String> {
            val bySubject = HashMap<String, String>()
            val byBody = HashMap<String, String>()
            for (record in log.split('\u001e')) {
                val hash = record.substringBefore('\u001f', "").trim()
                if (hash.isEmpty()) continue
                val message = record.substringAfter('\u001f')
                ID.findAll(message.substringBefore('\n')).forEach { bySubject.putIfAbsent(it.value, hash) }
                ID.findAll(message.substringAfter('\n', "")).forEach { byBody[it.value] = hash }
            }
            return byBody + bySubject
        }
    }
}

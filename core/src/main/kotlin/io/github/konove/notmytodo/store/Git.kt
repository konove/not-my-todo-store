package io.github.konove.notmytodo.store

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * What the store asks of git. Where a repository is and what its remotes are is read from the files,
 * so a project is found without git installed; the log and names of commits need the git command.
 */
class Git(private val executable: String = "git", private val timeoutMs: Long = 5000) {
    /** The nearest folder at or above [folder] with a .git directory or file. */
    fun root(folder: Path): Path? {
        var dir: Path? = folder.toAbsolutePath().normalize()
        while (dir != null) {
            if (Files.exists(dir.resolve(".git"))) return dir
            dir = dir.parent
        }
        return null
    }

    /** The remotes of the repository at [root], normalized; for a worktree, those of its repository. */
    fun remotes(root: Path): List<String> {
        val config = gitDir(root)?.let(::commonDir)?.resolve("config") ?: return emptyList()
        val text = try {
            Files.readString(config)
        } catch (_: IOException) {
            return emptyList()
        }
        val urls = ArrayList<String>()
        var inRemote = false
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inRemote = REMOTE.matches(line)
                continue
            }
            if (inRemote) URL.matchEntire(line)?.let { urls += Remotes.normalize(it.groupValues[1].trim().removeSurrounding("\"")) }
        }
        return urls.distinct()
    }

    /**
     * The root of the main worktree when [root] is a linked worktree: the folder that holds the
     * repository's own .git. Null for the main worktree itself and for a submodule.
     */
    fun mainRoot(root: Path): Path? {
        val dir = gitDir(root) ?: return null
        if (!Files.isRegularFile(dir.resolve("commondir"))) return null
        return commonDir(dir).parent
    }

    /** The output of git [args] run in [dir]; null when git is not there, fails or takes too long. */
    fun run(dir: Path, vararg args: String): String? {
        val process = try {
            ProcessBuilder(listOf(executable) + args).directory(dir.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (_: IOException) {
            return null
        }
        val output = CompletableFuture.supplyAsync { process.inputStream.readAllBytes() }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) return null
        return try {
            String(output.get(timeoutMs, TimeUnit.MILLISECONDS), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** The repository's own folder: .git itself, or the one a .git file (worktree, submodule) names. */
    private fun gitDir(root: Path): Path? {
        val dot = root.resolve(".git")
        if (Files.isDirectory(dot)) return dot
        if (!Files.isRegularFile(dot)) return null
        val target = try {
            Files.readString(dot).lines().firstOrNull { it.startsWith("gitdir:") }?.removePrefix("gitdir:")?.trim()
        } catch (_: IOException) {
            null
        } ?: return null
        return root.resolve(target).normalize()
    }

    /** Where a worktree's repository keeps its config. */
    private fun commonDir(gitDir: Path): Path {
        val common = gitDir.resolve("commondir")
        return if (Files.isRegularFile(common)) gitDir.resolve(Files.readString(common).trim()).normalize() else gitDir
    }

    private companion object {
        val REMOTE = Regex("^\\[remote\\s+\"[^\"]*\"]$")
        val URL = Regex("^url\\s*=\\s*(.+)$")
    }
}

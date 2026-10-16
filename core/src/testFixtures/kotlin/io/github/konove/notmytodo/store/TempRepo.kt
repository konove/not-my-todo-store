package io.github.konove.notmytodo.store

import java.nio.file.Files
import java.nio.file.Path

/** Real git repositories for tests. */
object TempRepo {
    /** A repository at [dir] with one empty commit and, when given, the remote [origin]. */
    fun create(dir: Path, origin: String? = null): Path {
        Files.createDirectories(dir)
        git(dir, "init", "-q", "-b", "main")
        origin?.let { git(dir, "remote", "add", "origin", it) }
        commit(dir, "Start")
        return dir
    }

    fun commit(dir: Path, message: String): String =
        git(dir, "-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "commit.gpgsign=false", "commit", "-q", "--allow-empty", "-m", message)

    fun git(dir: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(dir.toFile()).redirectErrorStream(true).start()
        val out = String(process.inputStream.readAllBytes())
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $out" }
        return out
    }
}

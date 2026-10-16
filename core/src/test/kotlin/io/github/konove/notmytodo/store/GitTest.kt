package io.github.konove.notmytodo.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class GitTest {
    private val tmp = Files.createTempDirectory("nmt-git")
    private val git = Git()

    @Test
    fun `the root is found from a folder inside the repository`() {
        val repo = TempRepo.create(tmp.resolve("a"), "git@github.com:me/a.git")
        val sub = Files.createDirectories(repo.resolve("src/main"))
        assertEquals(repo.toRealPath(), git.root(sub)?.toRealPath())
        assertEquals(listOf("github.com/me/a"), git.remotes(repo))
    }

    @Test
    fun `a worktree has the remotes of its repository`() {
        val repo = TempRepo.create(tmp.resolve("b"), "https://github.com/me/b.git")
        val worktree = tmp.resolve("b-wt")
        TempRepo.git(repo, "worktree", "add", "-q", worktree.toString())
        assertEquals(worktree.toRealPath(), git.root(worktree)?.toRealPath())
        assertEquals(listOf("github.com/me/b"), git.remotes(worktree))
    }

    @Test
    fun `a folder outside any repository has no root of its own`() {
        val plain = Files.createDirectories(tmp.resolve("plain"))
        val root = git.root(plain)
        assertTrue(root == null || !root.startsWith(plain))
        assertEquals(emptyList<String>(), git.remotes(plain))
    }

    @Test
    fun `run gives the output, or null when git fails or is not there`() {
        val repo = TempRepo.create(tmp.resolve("c"))
        assertEquals("true", git.run(repo, "rev-parse", "--is-inside-work-tree")?.trim())
        assertNull(git.run(repo, "no-such-command"))
        assertNull(Git("no-such-git-binary").run(repo, "status"))
    }
}

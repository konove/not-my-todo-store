package io.github.konove.notmytodo.store

import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.TodoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FixCommitsTest {
    private fun log(vararg commits: Pair<String, String>) = commits.joinToString("") { (hash, message) -> "$hash\u001f$message\n\u001e\n" }

    @Test
    fun `the newest commit that names an item is the one that fixed it`() {
        val found = FixCommits.parse(
            log(
                "ccc3333" to "Fix T-12: cache the path",
                "bbb2222" to "Tidy up\n\nAlso closes T-7 and T-12.",
                "aaa1111" to "Start on T-7",
            )
        )
        assertEquals(mapOf("T-12" to "ccc3333", "T-7" to "bbb2222"), found)
    }

    @Test
    fun `only whole item ids count`() {
        val found = FixCommits.parse(log("aaa1111" to "Bump LIST-12, XT-3 and T-0; see T-45x, t-9 and (T-8)."))
        assertEquals(mapOf("T-8" to "aaa1111"), found)
        assertEquals(emptyMap<String, String>(), FixCommits.parse(""))
    }

    @Test
    fun `the commit that names an item is read from the checkout`() {
        val repo = TempRepo.create(Files.createTempDirectory("nmt-fix").resolve("r"))
        TempRepo.commit(repo, "Fix T-3: the thing")
        val fixes = FixCommits(repo, Git())
        val item = TodoItem("T-3", "x", status = Status.FIXED)
        assertTrue(fixes.refresh())
        val head = TempRepo.git(repo, "rev-parse", "--short", "HEAD").trim()
        assertEquals(head, fixes.shown(item).fixedIn)
        assertEquals(head, fixes.resolve("HEAD"))
        assertNull(fixes.resolve("abc1234"))
        assertNull(fixes.resolve("no-such-branch"))
    }
}

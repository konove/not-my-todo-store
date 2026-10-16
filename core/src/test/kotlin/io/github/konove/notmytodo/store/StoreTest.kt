package io.github.konove.notmytodo.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class StoreTest {
    private val tmp: Path = Files.createTempDirectory("nmt-projects").toRealPath()
    private val home: Path = Files.createDirectories(tmp.resolve("home"))
    private val store = Store(tmp.resolve("store"), home = home)

    private fun ensure(folder: Path) = store.ensure(store.resolve(folder))

    @Test
    fun `a repository becomes a project named after its folder, found again from inside it`() {
        val repo = TempRepo.create(tmp.resolve("Web App"), "git@github.com:me/web.git")
        assertNull(store.resolve(repo).record)
        val made = ensure(repo)
        assertTrue(made.created)
        assertEquals("web-app", made.record.key)
        assertEquals(listOf("github.com/me/web"), made.record.remotes)
        assertEquals(listOf(repo.toString()), made.record.checkouts)
        assertEquals("web-app", store.resolve(Files.createDirectories(repo.resolve("src"))).record!!.key)
        assertFalse(ensure(repo).created)
    }

    @Test
    fun `a second clone of the same remote joins the project`() {
        val first = TempRepo.create(tmp.resolve("a/web"), "git@github.com:me/web.git")
        val second = TempRepo.create(tmp.resolve("b/web-copy"), "https://github.com/me/web")
        ensure(first)
        val found = store.resolve(second).record!!
        assertEquals("web", found.key)
        assertEquals(listOf(first.toString(), second.toString()), store.project("web")!!.checkouts)
    }

    @Test
    fun `a worktree joins the project of its repository`() {
        val repo = TempRepo.create(tmp.resolve("lib"), "git@github.com:me/lib.git")
        ensure(repo)
        val worktree = tmp.resolve("lib-feature")
        TempRepo.git(repo, "worktree", "add", "-q", worktree.toString())
        assertEquals("lib", store.resolve(worktree).record!!.key)
    }

    @Test
    fun `a folder outside git is a project of its own path`() {
        val notes = Files.createDirectories(tmp.resolve("notes"))
        val made = ensure(notes)
        assertEquals("notes", made.record.key)
        assertEquals(emptyList<String>(), made.record.remotes)
        assertEquals("notes", store.resolve(notes).record!!.key)
    }

    @Test
    fun `home and the file system root are never a project`() {
        assertNull(store.resolve(home).root)
        assertNull(store.resolve(Path.of("/")).root)
        assertEquals(Store.INBOX, ensure(home).record.key)
    }

    @Test
    fun `two repositories with the same folder name get two keys`() {
        ensure(TempRepo.create(tmp.resolve("x/api"), "git@github.com:me/api.git"))
        assertEquals("api-2", ensure(TempRepo.create(tmp.resolve("y/api"), "git@github.com:other/api.git")).record.key)
    }

    @Test
    fun `a deleted checkout is dropped when its project is looked up`() {
        val first = TempRepo.create(tmp.resolve("p/one"), "git@github.com:me/one.git")
        val second = TempRepo.create(tmp.resolve("q/one"), "git@github.com:me/one.git")
        ensure(first)
        store.resolve(second)
        first.toFile().deleteRecursively()
        store.resolve(second)
        assertEquals(listOf(second.toString()), store.project("one")!!.checkouts)
    }

    @Test
    fun `two stores making the project of one folder at once make one`() {
        val repo = TempRepo.create(tmp.resolve("race"), "git@github.com:me/race.git")
        val other = Store(tmp.resolve("store"), home = home)
        val keys = listOf(store, other).map { s -> Thread { s.ensure(s.resolve(repo)) } }
        keys.forEach(Thread::start)
        keys.forEach(Thread::join)
        assertEquals(listOf("race"), store.projects().map { it.key })
    }

    @Test
    fun `find reads a qualified id`() {
        val repo = TempRepo.create(tmp.resolve("infra"), "git@github.com:me/infra.git")
        ensure(repo)
        store.items("infra").create(Draft("there"))
        assertEquals("there", store.find("infra/T-1")!!.title)
        assertNull(store.find("infra/T-2"))
        assertNull(store.find("nowhere/T-1"))
        assertNull(store.find("T-1"))
    }
}

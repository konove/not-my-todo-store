package io.github.konove.notmytodo.server

import com.google.gson.JsonParser
import io.github.konove.notmytodo.store.Draft
import io.github.konove.notmytodo.store.Store
import io.github.konove.notmytodo.store.TempRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AcrossProjectsTest {
    private val tmp = Files.createTempDirectory("nmt-across").toRealPath()
    private val home = Files.createDirectories(tmp.resolve("home"))
    private val store = Store(tmp.resolve("store"), home = home)
    private val web = TempRepo.create(tmp.resolve("web"), "git@github.com:me/web.git").also { store.ensure(store.resolve(it)) }
    private val api = TempRepo.create(tmp.resolve("api"), "git@github.com:me/api.git").also { store.ensure(store.resolve(it)) }

    init {
        store.items("web").create(Draft("web one"))
        store.items("api").create(Draft("api one"))
        store.items("api").create(Draft("api two", blockedBy = listOf("web/T-1")))
    }

    @Test
    fun `star lists every project with qualified ids, and is the default outside any project`() {
        val rows = JsonParser.parseString(TodoTools(Workspace(store, web)).list(compact = true, project = "*")).asJsonArray.map { it.asJsonObject }
        assertEquals(listOf("api/T-1", "api/T-2", "web/T-1"), rows.map { it.get("id").asString })
        assertEquals("api", rows[0].get("project").asString)
        assertEquals("web/T-1", rows[1].get("blockedBy").asString)
        val fromHome = JsonParser.parseString(TodoTools(Workspace(store, home)).list(compact = true)).asJsonArray
        assertEquals(3, fromHome.size())
        val full = JsonParser.parseString(TodoTools(Workspace(store, home)).list()).asJsonArray.map { it.asJsonObject }
        assertEquals("api/T-1", full[0].get("id").asString)
    }

    @Test
    fun `todo_projects counts what is open in each`() {
        val projects = JsonParser.parseString(TodoTools(Workspace(store, web)).projects()).asJsonArray.map { it.asJsonObject }
        assertEquals(listOf("api", "web"), projects.map { it.get("key").asString })
        assertEquals(2, projects[0].get("open").asInt)
        assertTrue(projects[1].get("current").asBoolean)
        assertEquals("github.com/me/web", projects[1].getAsJsonArray("remotes")[0].asString)
    }

    @Test
    fun `todo_move moves an item from the inbox into a project`() {
        store.items(store.inbox().key).create(Draft("idea"))
        val tools = TodoTools(Workspace(store, web))
        val reply = JsonParser.parseString(tools.move("inbox/T-1", "api")).asJsonObject
        assertEquals("api/T-3", reply.getAsJsonObject("moved").get("inbox/T-1").asString)
        assertEquals("inbox/T-1", store.items("api").find("T-3")!!.movedFrom)
        try {
            tools.move("T-1", "nowhere")
            throw AssertionError("expected a ToolError")
        } catch (e: ToolError) {
            assertTrue(e.message!!.contains("nowhere"))
        }
    }
}

package io.github.konove.notmytodo.server

import com.google.gson.JsonParser
import io.github.konove.notmytodo.model.Author
import io.github.konove.notmytodo.model.Effort
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.store.Draft
import io.github.konove.notmytodo.store.Import
import io.github.konove.notmytodo.store.ImportResult
import io.github.konove.notmytodo.store.ItemStore
import io.github.konove.notmytodo.store.Store
import io.github.konove.notmytodo.store.TempRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class TodoToolsTest {
    private val tmp: Path = Files.createTempDirectory("nmt-tools").toRealPath()
    private val home: Path = Files.createDirectories(tmp.resolve("home"))
    private val repo: Path = TempRepo.create(tmp.resolve("demo"), "git@github.com:me/demo.git")
    private val stores = Store(tmp.resolve("store"), home = home).also { it.ensure(it.resolve(repo)) }
    private val tools = TodoTools(Workspace(stores, repo))
    /** The items of the repository's project, read again. */
    private val store: ItemStore get() = stores.items("demo")
    private val text = "one\ntwo\nthree\nfour\n"

    /** Writes [name] in the repository and returns its path as the tools take it. */
    private fun write(name: String, content: String = text): String {
        val file = repo.resolve(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        return name
    }

    private fun failsWith(part: String, call: () -> Unit) {
        try {
            call()
            fail("expected a ToolError mentioning $part")
        } catch (e: ToolError) {
            assertTrue("${e.message} should mention $part", e.message!!.contains(part))
        }
    }

    /** The text of every item file of the repository's project. */
    private fun onDisk(): String =
        Files.list(tmp.resolve("store/projects/demo/items")).use { s -> s.sorted().map { Files.readString(it) }.toList() }.joinToString("\n")

    @Test
    fun `create makes an agent item anchored to current code`() {
        val path = write("a.txt")
        val json = JsonParser.parseString(tools.create("from agent", "why", "p1", "perf, #Bug", path, 2, 3)).asJsonObject
        assertEquals("T-1", json.get("id").asString)
        val item = store.find("T-1")!!
        assertEquals(Author.AGENT, item.author)
        assertEquals(listOf("perf", "bug"), item.tags)
        assertEquals("two\nthree", item.anchor!!.text)
    }

    @Test
    fun `create without a path makes a plain note with defaults`() {
        tools.create("note", null, null, null, null, null, null)
        val item = store.find("T-1")!!
        assertNull(item.anchor)
        assertEquals("p2", item.priority.json)
    }

    @Test
    fun `create rejects bad input`() {
        val path = write("b.txt")
        failsWith("title") { tools.create("  ", null, null, null, null, null, null) }
        failsWith("priority") { tools.create("x", null, "urgent", null, null, null, null) }
        failsWith("no/such.txt") { tools.create("x", null, null, null, "no/such.txt", 1, 1) }
        failsWith("startLine") { tools.create("x", null, null, null, path, null, 2) }
        failsWith("path") { tools.create("x", null, null, null, null, 2, null) }
        failsWith("$path:two") { tools.create("x", null, null, null, null, null, null, "$path:two") }
        failsWith("no/such.txt") { tools.create("x", null, null, null, path, 1, 1, "no/such.txt") }
        failsWith("5 lines") { tools.create("x", null, null, null, path, 4, 9) }
        assertEquals(0, store.items.size)
    }

    @Test
    fun `list filters and leaves out anchor text`() {
        val path = write("c.txt")
        tools.create("a", null, "p1", "perf", path, 1, 1)
        tools.create("b", null, "p2", "bug", null, null, null)
        tools.update("T-2", null, null, null, null, "done")
        val all = JsonParser.parseString(tools.list(null, null, null, null)).asJsonArray
        assertEquals(2, all.size())
        assertFalse(all[0].asJsonObject.getAsJsonArray("anchors")[0].asJsonObject.has("text"))
        assertEquals(1, JsonParser.parseString(tools.list("done", null, null, null)).asJsonArray.size())
        assertEquals(1, JsonParser.parseString(tools.list(null, "#perf", null, null)).asJsonArray.size())
        assertEquals(1, JsonParser.parseString(tools.list(null, null, "p1", null)).asJsonArray.size())
        assertEquals(1, JsonParser.parseString(tools.list(null, null, null, path)).asJsonArray.size())
        failsWith("status") { tools.list("later", null, null, null) }
    }

    @Test
    fun `list combines tags, directory, text and statuses`() {
        val path = write("foo/bar/c.txt")
        val other = write("foobar/c.txt")
        val dir = path.removeSuffix("/bar/c.txt")
        tools.create("Cache the path", "squad wide", null, "bugs td", path, 1, 2)
        tools.create("Drop the CACHE", null, null, "bugs td needs-decision", path, 3, 3)
        tools.create("Cache elsewhere", null, null, "bugs td", other, 1, 1)
        tools.create("Rename it", "the cache key", null, "bugs", path, 4, 4)
        tools.create("A note about the cache", null, null, "bugs td", null, null, null)
        tools.update("T-4", null, null, null, null, "in_progress")
        fun ids(json: String) = JsonParser.parseString(json).asJsonArray.map { it.asJsonObject.get("id").asString }

        assertEquals(listOf("T-1"), ids(tools.list("open", "bugs, td", null, "$dir/", "needs-decision", "cache")))
        assertEquals(listOf("T-1", "T-2", "T-4"), ids(tools.list(path = dir)))
        assertEquals(listOf("T-1", "T-2", "T-4"), ids(tools.list(path = path)))
        assertEquals(listOf("T-1", "T-4"), ids(tools.list(path = dir, withoutTags = "#needs-decision")))
        assertEquals(listOf("T-1", "T-2", "T-4", "T-5"), ids(tools.list(text = "CACHE the")))
        assertEquals(listOf("T-1"), ids(tools.list(text = "squad cache")))
        assertEquals(listOf("T-4"), ids(tools.list(status = "in_progress done")))
        assertEquals(5, ids(tools.list(status = "", tags = " ", path = "", text = "")).size)
        failsWith("status") { tools.list(status = "open later") }
    }

    @Test
    fun `list can return only items whose anchor is lost`() {
        val path = write("c.txt")
        tools.create("found", null, null, "bugs", path, 1, 1)
        tools.create("gone", null, null, "bugs", path, 3, 3)
        tools.create("gone too", null, null, null, path, 4, 4)
        tools.create("note", null, null, "bugs", null, null, null)
        // The code of T-2 and T-3 is taken out of the file.
        write(path, "one\ntwo\n")
        fun ids(json: String) = JsonParser.parseString(json).asJsonArray.map { it.asJsonObject.get("id").asString }

        assertEquals(listOf("T-2", "T-3"), ids(tools.list(lost = true)))
        assertEquals(listOf("T-2"), ids(tools.list(tags = "bugs", lost = true)))
        assertEquals(listOf("T-2", "T-3"), ids(tools.list(lost = true, compact = true)))
        assertEquals(4, ids(tools.list()).size)
    }

    @Test
    fun `the anchors of a fixed or closed item stay where they were and are never lost`() {
        val path = write("frozen.txt")
        tools.create("fixed", null, null, null, path, 2, 2)
        tools.create("open", null, null, null, path, 3, 3)
        tools.create("closed", null, null, null, path, 4, 4)
        tools.update("T-3", null, null, null, null, "wont_fix")
        // The commit that fixes T-1 takes its code out and moves the rest.
        write(path, "zero\nzero\none\nthree\nfour\n")
        val row = JsonParser.parseString(tools.update("T-1", null, null, null, null, "fixed", resolution = "Took it out.")).asJsonObject
        assertFalse(row.has("lost"))
        assertEquals("$path:2", row.get("at").asString)
        fun ids(json: String) = JsonParser.parseString(json).asJsonArray.map { it.asJsonObject.get("id").asString }
        assertEquals(emptyList<String>(), ids(tools.list(lost = true)))

        val anchor = JsonParser.parseString(tools.get("T-1")).asJsonObject.getAsJsonArray("anchors")[0].asJsonObject
        assertEquals(setOf("path", "startLine", "endLine", "lost", "text"), anchor.keySet())
        assertEquals("two", anchor.get("text").asString)
        // The open item follows its code, and the closed one stays where it was.
        val rows = JsonParser.parseString(tools.list(compact = true)).asJsonArray.map { it.asJsonObject }
        assertEquals("$path:4", rows[1].get("at").asString)
        assertEquals("$path:4", rows[2].get("at").asString)
        Files.delete(repo.resolve(path))
        assertEquals(listOf("T-2"), ids(tools.list(lost = true)))

        // Opened again, it is found as the code is now.
        tools.update("T-1", null, null, null, null, "open")
        assertEquals(listOf("T-1", "T-2"), ids(tools.list(lost = true)))
    }

    @Test
    fun `compact list has one short line per item`() {
        val path = write("c.txt")
        tools.create("a", "long details", "p1", "perf", path, 2, 3)
        tools.create("b", "more details", null, null, path, 4, 4)
        tools.create("c", null, null, null, null, null, null)
        val out = tools.list(compact = true)
        assertEquals(5, out.lines().size)
        val rows = JsonParser.parseString(out).asJsonArray.map { it.asJsonObject }
        assertEquals(setOf("id", "title", "priority", "status", "tags", "at"), rows[0].keySet())
        assertEquals("$path:2-3", rows[0].get("at").asString)
        assertEquals("$path:4", rows[1].get("at").asString)
        assertFalse(rows[2].has("at"))
        assertEquals("[]", tools.list(status = "done", compact = true))
    }

    @Test
    fun `get returns live lines and code after the file changed`() {
        val path = write("d.txt")
        tools.create("a", null, null, null, path, 2, 2)
        Files.writeString(repo.resolve(path), "zero\n" + Files.readString(repo.resolve(path)))
        val json = JsonParser.parseString(tools.get("T-1")).asJsonObject
        assertEquals(3, json.getAsJsonArray("anchors")[0].asJsonObject.get("startLine").asInt)
        assertEquals("two", json.getAsJsonArray("anchors")[0].asJsonObject.get("code").asString)
        failsWith("T-9") { tools.get("T-9") }
    }

    @Test
    fun `update changes only the given fields`() {
        store.create(Draft("a", details = "keep", tags = listOf("x")))
        val json = JsonParser.parseString(tools.update("T-1", "b", null, null, null, "fixed")).asJsonObject
        assertEquals("b", json.get("title").asString)
        val item = store.find("T-1")!!
        assertEquals("keep", item.details)
        assertEquals(listOf("x"), item.tags)
        assertEquals(Status.FIXED, item.status)
        failsWith("status") { tools.update("T-1", null, null, null, null, "finished") }
        failsWith("T-9") { tools.update("T-9", "x", null, null, null, null) }
    }

    @Test
    fun `update re-attaches an item`() {
        val path = write("e.txt")
        tools.create("a", null, null, null, path, 2, 2)
        tools.update("T-1", null, null, null, null, null, null, 3, 4)
        assertEquals("three\nfour", store.find("T-1")!!.anchor!!.text)
        val json = JsonParser.parseString(tools.get("T-1")).asJsonObject
        assertEquals(3, json.getAsJsonArray("anchors")[0].asJsonObject.get("startLine").asInt)
        assertEquals("three\nfour", json.getAsJsonArray("anchors")[0].asJsonObject.get("code").asString)

        store.update("T-1") { it.copy(anchors = listOf(it.anchor!!.copy(lost = true))) }
        tools.update("T-1", null, null, null, null, null, path, 1, null)
        val anchor = store.find("T-1")!!.anchor!!
        assertFalse(anchor.lost)
        assertEquals("one", anchor.text)

        failsWith("5 lines") { tools.update("T-1", null, null, null, null, null, null, 9, null) }
        tools.update("T-1", null, null, null, null, null, path, null, null)
        assertEquals(listOf(path), store.find("T-1")!!.anchors.map { it.place })
        failsWith("startLine") { tools.update("T-1", null, null, null, null, null, path, null, 2) }
        store.create(Draft("note"))
        failsWith("path") { tools.update("T-2", null, null, null, null, null, null, 1, null) }
    }

    @Test
    fun `broken file is reported as a tool error`() {
        Files.writeString(tmp.resolve("store/projects/demo/project.json"), "{ broken")
        failsWith("Fix project.json there.") { tools.list(null, null, null, null) }
        failsWith("project.json") { tools.create("x", null, null, null, null, null, null) }
    }

    @Test
    fun `create sees files as they are on disk right now`() {
        write("fresh-on-disk.txt", "a\nb\n")
        tools.create("first", null, null, null, "fresh-on-disk.txt", 2, 2)
        assertEquals("b", store.find("T-1")!!.anchor!!.text)
        write("fresh-on-disk.txt", "a\nc\n")
        tools.create("second", null, null, null, "fresh-on-disk.txt", 2, 2)
        assertEquals("c", store.find("T-2")!!.anchor!!.text)
    }

    @Test
    fun `comment adds a note and does not touch the details`() {
        store.create(Draft("a", details = "keep"))
        tools.comment("T-1", "found the cause")
        store.update("T-1") { it.copy(details = "edited in the IDE") }
        val json = JsonParser.parseString(tools.comment("T-1", "and a fix", full = true)).asJsonObject
        assertEquals("edited in the IDE", json.get("details").asString)
        val item = store.find("T-1")!!
        assertEquals("edited in the IDE", item.details)
        assertEquals(listOf("found the cause", "and a fix"), item.comments.map { it.text })
        assertTrue(item.comments.all { it.author == Author.AGENT })
        val got = JsonParser.parseString(tools.get("T-1")).asJsonObject.getAsJsonArray("comments")
        assertEquals("and a fix", got[1].asJsonObject.get("text").asString)
        failsWith("text") { tools.comment("T-1", "  ") }
        failsWith("T-9") { tools.comment("T-9", "x") }
    }

    @Test
    fun `create attaches to a whole file and to several places`() {
        val a = write("m1.txt")
        val b = write("m2.txt")
        tools.create("whole", null, null, null, a, null, null)
        assertEquals(listOf(a), store.find("T-1")!!.anchors.map { it.place })
        assertTrue(store.find("T-1")!!.anchor!!.isFile)

        tools.create("several", null, null, null, a, 2, 3, "$b:4, $b\n./$a:2-3")
        assertEquals(listOf("$a:2-3", "$b:4", b), store.find("T-2")!!.anchors.map { it.place })
        assertEquals("four", store.find("T-2")!!.anchors[1].text)

        val got = JsonParser.parseString(tools.get("T-2")).asJsonObject.getAsJsonArray("anchors").map { it.asJsonObject }
        assertEquals("two\nthree", got[0].get("code").asString)
        assertEquals("four", got[1].get("code").asString)
        assertFalse(got[2].has("code"))
        assertFalse(got[2].has("startLine"))

        val rows = tools.list(compact = true).lines().filter { it.startsWith("{") }.map { JsonParser.parseString(it.trimEnd(',')).asJsonObject }
        assertEquals("$a:2-3, $b:4, $b", rows[1].get("at").asString)
        assertEquals(listOf("T-2"), JsonParser.parseString(tools.list(path = b)).asJsonArray.map { it.asJsonObject.get("id").asString })
        val listed = JsonParser.parseString(tools.list(path = b)).asJsonArray[0].asJsonObject.getAsJsonArray("anchors")
        assertEquals(3, listed.size())
        assertFalse(listed[1].asJsonObject.has("text"))
    }

    @Test
    fun `update sets the whole list of places or moves the one in a file`() {
        val a = write("n1.txt")
        val b = write("n2.txt")
        tools.create("x", null, null, null, a, 2, 2, "$b:1")
        val kept = store.find("T-1")!!.anchors[1]

        tools.update("T-1", null, null, null, null, null, a, 3, 4)
        assertEquals(listOf("$a:3-4", "$b:1"), store.find("T-1")!!.anchors.map { it.place })
        failsWith("2 places") { tools.update("T-1", null, null, null, null, null, null, 1, null) }
        failsWith("places") { tools.update("T-1", null, null, null, null, null, "other.txt", 1, null) }
        failsWith("not both") { tools.update("T-1", null, null, null, null, null, a, 1, null, "$a:1") }

        tools.update("T-1", null, null, null, null, null, places = "$b:1, $a")
        assertEquals(listOf("$b:1", a), store.find("T-1")!!.anchors.map { it.place })
        assertEquals(kept, store.find("T-1")!!.anchors[0])

        tools.update("T-1", null, null, null, null, null, places = "")
        assertTrue(store.find("T-1")!!.anchors.isEmpty())
        tools.update("T-1", null, null, null, null, null, null, null, null)
        assertTrue(store.find("T-1")!!.anchors.isEmpty())
    }

    @Test
    fun `links are set, replaced and taken away`() {
        tools.create("whole", null, null, null, null, null, null)
        tools.create("first", null, null, null, null, null, null, parent = "T-1")
        tools.create("second", null, null, null, null, null, null, blockedBy = "T-2", parent = "t-1")
        tools.create("again", null, null, null, null, null, null, duplicateOf = "2")
        assertEquals(listOf("T-2"), store.find("T-3")!!.blockedBy)
        assertEquals("T-1", store.find("T-3")!!.parent)
        assertEquals("T-2", store.find("T-4")!!.duplicateOf)

        tools.update("T-3", "renamed", null, null, null, null)
        assertEquals(listOf("T-2"), store.find("T-3")!!.blockedBy)
        tools.update("T-3", null, null, null, null, null, blockedBy = "T-2, T-4")
        assertEquals(listOf("T-2", "T-4"), store.find("T-3")!!.blockedBy)
        assertEquals("T-1", store.find("T-3")!!.parent)
        tools.update("T-3", null, null, null, null, null, blockedBy = "", parent = "")
        assertTrue(store.find("T-3")!!.blockedBy.isEmpty())
        assertNull(store.find("T-3")!!.parent)
        tools.update("T-4", null, null, null, null, null, duplicateOf = " ")
        assertNull(store.find("T-4")!!.duplicateOf)

        failsWith("not an item id") { tools.update("T-3", null, null, null, null, null, blockedBy = "second") }
        failsWith("T-9") { tools.update("T-3", null, null, null, null, null, parent = "T-9") }
        failsWith("itself") { tools.update("T-3", null, null, null, null, null, blockedBy = "T-3") }
        failsWith("one level") { tools.create("deep", null, null, null, null, null, null, parent = "T-2") }
    }

    @Test
    fun `list and get say what is blocked and what an item is made of`() {
        tools.create("whole", null, null, null, null, null, null)
        tools.create("first", null, null, null, null, null, null, parent = "T-1")
        tools.create("second", null, null, null, null, null, null, blockedBy = "T-2", parent = "T-1")
        fun ids(json: String) = JsonParser.parseString(json).asJsonArray.map { it.asJsonObject.get("id").asString }

        assertEquals(listOf("T-3"), ids(tools.list(blocked = true)))
        assertEquals(listOf("T-1", "T-2"), ids(tools.list(blocked = false)))
        assertEquals(listOf("T-2", "T-3"), ids(tools.list(parent = "T-1")))
        assertEquals(listOf("T-2", "T-3"), ids(tools.list(parent = "demo/T-1")))
        val row = JsonParser.parseString(tools.list(compact = true)).asJsonArray[2].asJsonObject
        assertEquals("T-2", row.get("blockedBy").asString)
        assertEquals("T-1", row.get("parent").asString)
        assertTrue(JsonParser.parseString(tools.list()).asJsonArray[2].asJsonObject.get("blocked").asBoolean)
        assertTrue(JsonParser.parseString(tools.get("T-3")).asJsonObject.get("blocked").asBoolean)
        assertEquals("[\"T-2\",\"T-3\"]", JsonParser.parseString(tools.get("T-1")).asJsonObject.get("children").toString())

        tools.update("T-2", null, null, null, null, "done")
        assertEquals(emptyList<String>(), ids(tools.list(blocked = true)))
        assertFalse(JsonParser.parseString(tools.get("T-3")).asJsonObject.has("blocked"))
        assertFalse(JsonParser.parseString(tools.list(compact = true)).asJsonArray[2].asJsonObject.has("blockedBy"))
        assertEquals(listOf("T-2"), store.find("T-3")!!.blockedBy)
    }

    @Test
    fun `get with parts gives each part with its details and its anchors without code`() {
        val path = write("parts.txt")
        tools.create("whole", null, null, null, null, null, null)
        tools.create("first", "do this first", null, null, path, 2, 3, parent = "T-1")
        tools.create("second", "then this", null, null, null, null, null, blockedBy = "T-2", parent = "T-1")
        tools.create("elsewhere", null, null, null, null, null, null)
        // The code of the first part moves down a line.
        write(path, "zero\n$text")
        val json = JsonParser.parseString(tools.get("T-1", parts = true)).asJsonObject
        val parts = json.getAsJsonArray("children").map { it.asJsonObject }
        assertEquals(listOf("T-2", "T-3"), parts.map { it.get("id").asString })
        assertEquals(listOf("do this first", "then this"), parts.map { it.get("details").asString })
        val anchor = parts[0].getAsJsonArray("anchors")[0].asJsonObject
        assertEquals(setOf("path", "startLine", "endLine", "lost"), anchor.keySet())
        assertEquals(3, anchor.get("startLine").asInt)
        assertTrue(parts[1].get("blocked").asBoolean)
        assertEquals("2 open", json.get("parts").asString)
        assertFalse(JsonParser.parseString(tools.get("T-4", parts = true)).asJsonObject.has("children"))
    }

    @Test
    fun `a parent is listed and read with the priority of its open parts`() {
        tools.create("whole", null, "p3", null, null, null, null)
        tools.create("first", null, "p1", null, null, null, null, parent = "T-1")
        tools.create("second", null, "p2", null, null, null, null, parent = "T-1")
        fun ids(json: String) = JsonParser.parseString(json).asJsonArray.map { it.asJsonObject.get("id").asString }
        fun got(id: String) = JsonParser.parseString(tools.get(id)).asJsonObject.get("priority").asString
        fun listed(compact: Boolean) = JsonParser.parseString(tools.list(compact = compact)).asJsonArray[0].asJsonObject.get("priority").asString

        assertEquals(listOf("T-1", "T-2"), ids(tools.list(priority = "p1")))
        assertEquals(emptyList<String>(), ids(tools.list(priority = "p3")))
        assertEquals("p1", listed(compact = true))
        assertEquals("p1", listed(compact = false))
        assertEquals("p1", got("T-1"))
        // What was set on the parent is kept, and is its priority again once no part is open.
        assertEquals("p3", store.find("T-1")!!.priority.json)
        tools.update("T-2", null, null, null, null, "done")
        assertEquals("p2", got("T-1"))
        assertEquals("p2", JsonParser.parseString(tools.update("T-1", null, null, "p1", null, null)).asJsonObject.get("priority").asString)
        tools.update("T-3", null, null, null, null, "wont_fix")
        assertEquals("p1", got("T-1"))
    }

    @Test
    fun `a parent says how many of its parts are open, fixed and closed`() {
        tools.create("whole", null, null, null, null, null, null)
        tools.create("first", null, null, null, null, null, null, parent = "T-1")
        tools.create("second", null, null, null, null, null, null, parent = "T-1")
        tools.create("third", null, null, null, null, null, null, parent = "T-1")
        tools.create("on its own", null, null, null, null, null, null)
        fun rows(json: String) = JsonParser.parseString(json).asJsonArray.map { it.asJsonObject }
        fun got(id: String) = JsonParser.parseString(tools.get(id)).asJsonObject

        // All open: a part in progress is open too. A group that counts none is left out.
        tools.update("T-2", null, null, null, null, "in_progress")
        assertEquals("3 open", rows(tools.list(compact = true))[0].get("parts").asString)
        assertEquals("3 open", got("T-1").get("parts").asString)
        // Only an item that has parts says so.
        assertFalse(rows(tools.list(compact = true)).drop(1).any { it.has("parts") })
        assertFalse(got("T-2").has("parts"))
        assertFalse(got("T-5").has("parts"))

        // A mix of the three: fixed is told apart from open, and from closed, as the user has still to look at it.
        tools.update("T-2", null, null, null, null, "fixed")
        tools.update("T-3", null, null, null, null, "done")
        assertEquals("1 open, 1 fixed, 1 closed", rows(tools.list(compact = true))[0].get("parts").asString)
        assertEquals("1 open, 1 fixed, 1 closed", got("T-1").get("parts").asString)
        assertEquals("1 open, 1 fixed, 1 closed", JsonParser.parseString(tools.comment("T-1", "a note")).asJsonObject.get("parts").asString)
        assertEquals("1 open, 1 fixed, 1 closed", JsonParser.parseString(tools.update("T-1", "the whole", null, null, null, null)).asJsonObject.get("parts").asString)

        // All fixed, which is when the parent itself can be set to fixed. A batch says how it is after the whole of it.
        val fixed = rows(tools.batch("""[{"id": "T-1", "priority": "p1"}, {"id": "T-3", "status": "fixed"}, {"id": "T-4", "status": "fixed"}]"""))
        assertEquals("3 fixed", fixed[0].get("parts").asString)
        assertFalse(fixed[1].has("parts"))
        assertEquals("3 fixed", rows(tools.list(compact = true))[0].get("parts").asString)
        assertEquals("3 fixed", got("T-1").get("parts").asString)

        // All closed, done or wont_fix.
        val closed = rows(tools.batch("""[{"id": "T-2", "status": "wont_fix"}, {"id": "T-3", "status": "done"}, {"id": "T-4", "status": "done"}, {"id": "T-1", "priority": "p2"}]"""))
        assertEquals("3 closed", closed[3].get("parts").asString)
        assertEquals("3 closed", rows(tools.list(compact = true))[0].get("parts").asString)
        assertEquals("3 closed", got("T-1").get("parts").asString)
        // The parent is not closed for the agent.
        assertEquals(Status.OPEN, store.find("T-1")!!.status)
    }

    @Test
    fun `source, commit and resolution are set, kept and taken away`() {
        tools.create("leftover", null, null, null, null, null, null, source = "30c6f6ae..e9ac6245")
        assertEquals("30c6f6ae..e9ac6245", store.find("T-1")!!.source)
        val json = JsonParser.parseString(
            tools.update("T-1", null, null, null, null, "fixed", fixedIn = "93e7970", resolution = "Cached the path.", full = true)
        ).asJsonObject
        assertEquals("93e7970", json.get("fixedIn").asString)
        assertEquals("Cached the path.", json.get("resolution").asString)
        tools.update("T-1", "renamed", null, null, null, null)
        assertEquals("Cached the path.", store.find("T-1")!!.resolution)
        assertEquals("30c6f6ae..e9ac6245", store.find("T-1")!!.source)
        assertEquals("93e7970", JsonParser.parseString(tools.list()).asJsonArray[0].asJsonObject.get("fixedIn").asString)
        tools.update("T-1", null, null, null, null, "open", source = "", fixedIn = "", resolution = "")
        val item = store.find("T-1")!!
        assertNull(item.source)
        assertNull(item.fixedIn)
        assertNull(item.resolution)
    }

    @Test
    fun `batch files ten items in one write`() {
        val path = write("batch.txt")
        var writes = 0
        val listener = { writes++; Unit }
        store.addListener(listener)
        val entries = (1..10).joinToString(",", "[", "]") {
            if (it == 1) """{"title": "item 1", "priority": "p1", "tags": ["perf", "#Bug"], "path": "$path", "startLine": 2, "endLine": 3}"""
            else """{"title": "item $it", "details": "why", "tags": "batch"}"""
        }
        val rows = JsonParser.parseString(tools.batch(entries)).asJsonArray
        store.removeListener(listener)
        assertEquals(1, writes)
        assertEquals((1..10).map { "T-$it" }, rows.map { it.asJsonObject.get("id").asString })
        assertEquals((1..10).map { "T-$it" }, store.items.map { it.id })
        val first = store.find("T-1")!!
        assertEquals(Author.AGENT, first.author)
        assertEquals(listOf("perf", "bug"), first.tags)
        assertEquals("two\nthree", first.anchor!!.text)
        assertEquals("why", store.find("T-10")!!.details)
        assertEquals("T-11", JsonParser.parseString(tools.create("next", null, null, null, null, null, null)).asJsonObject.get("id").asString)
    }

    @Test
    fun `batch re-tags twenty items in one write`() {
        repeat(20) { store.create(Draft("item $it", tags = listOf("old"))) }
        var writes = 0
        val listener = { writes++; Unit }
        store.addListener(listener)
        tools.batch((1..20).joinToString(",", "[", "]") { """{"id": "T-$it", "tags": "new mcp"}""" })
        store.removeListener(listener)
        assertEquals(1, writes)
        assertTrue(store.items.all { it.tags == listOf("new", "mcp") })
        assertEquals("item 0", store.find("T-1")!!.title)
    }

    @Test
    fun `batch creates and updates together, each as its own tool would`() {
        store.create(Draft("a", tags = listOf("keep")))
        store.create(Draft("b", duplicateOf = "T-1"))
        tools.batch(
            """[{"id": "t-1", "status": "fixed", "resolution": "did it", "fixedIn": "abc123"},
                {"id": "T-2", "duplicateOf": "", "blockedBy": "T-1"},
                {"title": "part", "parent": "T-1", "source": "run 7"}]"""
        )
        val a = store.find("T-1")!!
        assertEquals(Status.FIXED, a.status)
        assertEquals("did it", a.resolution)
        assertEquals("abc123", a.fixedIn)
        assertEquals(listOf("keep"), a.tags)
        val b = store.find("T-2")!!
        assertNull(b.duplicateOf)
        assertEquals(listOf("T-1"), b.blockedBy)
        val part = store.find("T-3")!!
        assertEquals("T-1", part.parent)
        assertEquals("run 7", part.source)
    }

    @Test
    fun `batch hands items back with the tag and the question in one write`() {
        store.create(Draft("a"))
        store.create(Draft("b"))
        var fired = 0
        val listener = { fired++; Unit }
        store.addListener(listener)
        try {
            tools.batch(
                """[{"id": "T-1", "tags": "needs-decision", "comment": "per unit or per cell?"},
                    {"id": "T-2", "comment": "half way"}]"""
            )
        } finally {
            store.removeListener(listener)
        }
        assertEquals(1, fired)
        val a = store.find("T-1")!!
        assertTrue(a.needsDecision)
        assertEquals(listOf(Author.AGENT to "per unit or per cell?"), a.comments.map { it.author to it.text })
        assertEquals(listOf("half way"), store.find("T-2")!!.comments.map { it.text })
        failsWith("entry 1: comment") { tools.batch("""[{"id": "T-1", "comment": " "}]""") }
        failsWith("comment cannot be set on a new item") { tools.batch("""[{"title": "new", "comment": "x"}]""") }
    }

    @Test
    fun `batch with a bad entry names it and changes nothing`() {
        store.create(Draft("a"))
        val before = onDisk()
        failsWith("entry 2") { tools.batch("""[{"title": "fine"}, {"id": "T-1", "priority": "urgent"}]""") }
        failsWith("entry 3") { tools.batch("""[{"title": "fine"}, {"id": "T-1", "title": "new"}, {"id": "T-9", "tags": "x"}]""") }
        failsWith("entry 2") { tools.batch("""[{"title": "fine"}, {"title": "part", "parent": "T-9"}]""") }
        failsWith("entry 1") { tools.batch("""[{"details": "no title"}]""") }
        failsWith("titel") { tools.batch("""[{"titel": "typo"}]""") }
        failsWith("status") { tools.batch("""[{"title": "new", "status": "fixed"}]""") }
        failsWith("no/such.txt") { tools.batch("""[{"id": "T-1", "places": "no/such.txt"}]""") }
        failsWith("entry 1") { tools.batch("""[{"title": 5}]""") }
        failsWith("JSON array") { tools.batch("""{"title": "not in an array"}""") }
        failsWith("JSON array") { tools.batch("not json") }
        failsWith("empty") { tools.batch("[]") }
        assertEquals(before, onDisk())
    }

    @Test
    fun `what is to be decided is set with the tag, kept and taken away`() {
        tools.create("pick a license", null, null, "legal", null, null, null, toDecide = "MIT or Apache?")
        val made = store.find("T-1")!!
        assertEquals("MIT or Apache?", made.toDecide)
        assertEquals(listOf("legal", "needs-decision"), made.tags)
        store.create(Draft("b", tags = listOf("x")))
        val json = JsonParser.parseString(tools.update("T-2", null, null, null, null, null, toDecide = "Which of the two?", full = true)).asJsonObject
        assertEquals("Which of the two?", json.get("toDecide").asString)
        assertEquals(listOf("x", "needs-decision"), store.find("T-2")!!.tags)
        // The tag goes when the work is done; what was to be decided stays on the item.
        tools.update("T-2", null, null, null, "x", "fixed")
        assertEquals("Which of the two?", store.find("T-2")!!.toDecide)
        assertFalse(store.find("T-2")!!.needsDecision)
        tools.update("T-2", null, null, null, null, null, toDecide = "")
        assertNull(store.find("T-2")!!.toDecide)
        assertFalse(store.find("T-2")!!.needsDecision)
        tools.batch("""[{"id": "T-2", "toDecide": "Again?"}, {"title": "new", "toDecide": "What?"}]""")
        assertTrue(store.find("T-2")!!.needsDecision)
        assertEquals("What?", store.find("T-3")!!.toDecide)
        assertTrue(store.find("T-3")!!.needsDecision)
    }

    @Test
    fun `decided records the questions, the options and the answers`() {
        store.create(Draft("pick a license", tags = listOf("needs-decision")))
        val json = JsonParser.parseString(
            tools.decided(
                "T-1",
                """[{"question": "Which license?", "options": ["MIT", "Apache 2.0"], "answer": "MIT"},
                    {"question": "Year?", "answer": "2026"}]""",
                full = true,
            )
        ).asJsonObject
        assertEquals(2, json.getAsJsonArray("decisions").size())
        tools.decided("t-1", """[{"question": "A header in every file?", "options": ["Yes", "No"], "answer": "Only in new ones"}]""")
        val item = store.find("T-1")!!
        assertEquals(
            listOf("Which license?" to "MIT", "Year?" to "2026", "A header in every file?" to "Only in new ones"),
            item.decisions.map { it.question to it.answer },
        )
        assertEquals(listOf("MIT", "Apache 2.0"), item.decisions[0].options)
        assertTrue(item.decisions.all { it.time.isNotEmpty() })
        assertFalse(item.needsDecision)
        assertEquals(Status.OPEN, item.status)
        failsWith("JSON array") { tools.decided("T-1", "not json") }
        failsWith("empty") { tools.decided("T-1", "[]") }
        failsWith("entry 2: answer") { tools.decided("T-1", """[{"question": "q", "answer": "a"}, {"question": "q"}]""") }
        failsWith("question") { tools.decided("T-1", """[{"answer": "a"}]""") }
        failsWith("options") { tools.decided("T-1", """[{"question": "q", "options": "MIT", "answer": "a"}]""") }
        failsWith("chosen") { tools.decided("T-1", """[{"question": "q", "chosen": "a"}]""") }
        failsWith("T-9") { tools.decided("T-9", """[{"question": "q", "answer": "a"}]""") }
        assertEquals(3, store.find("T-1")!!.decisions.size)
    }

    @Test
    fun `batch records decisions about several items`() {
        store.create(Draft("pick a license", tags = listOf("needs-decision", "legal")))
        store.create(Draft("pick a year", tags = listOf("needs-decision")))
        store.create(Draft("pick both", tags = listOf("needs-decision")))
        tools.batch(
            """[{"id": "T-1", "decisions": [{"question": "Which license?", "options": ["MIT", "Apache 2.0"], "answer": "MIT"}]},
                {"id": "T-2", "decisions": [{"question": "Year?", "answer": "2026"}], "status": "in_progress", "comment": "on it"},
                {"id": "T-3", "decisions": [{"question": "License?", "answer": "MIT"}], "toDecide": "Which year?"}]"""
        )
        val license = store.find("T-1")!!
        assertEquals(listOf("Which license?" to "MIT"), license.decisions.map { it.question to it.answer })
        assertEquals(listOf("MIT", "Apache 2.0"), license.decisions[0].options)
        assertEquals(listOf("legal"), license.tags)
        val year = store.find("T-2")!!
        assertEquals(listOf("2026"), year.decisions.map { it.answer })
        assertFalse(year.needsDecision)
        assertEquals(Status.IN_PROGRESS, year.status)
        assertEquals(listOf("on it"), year.comments.map { it.text })
        // What is still open makes the item wait again.
        val both = store.find("T-3")!!
        assertEquals(1, both.decisions.size)
        assertTrue(both.needsDecision)

        val before = onDisk()
        failsWith("entry 2: decision 1: answer") {
            tools.batch("""[{"id": "T-1", "decisions": [{"question": "q", "answer": "a"}]}, {"id": "T-2", "decisions": [{"question": "q"}]}]""")
        }
        failsWith("entry 1: decisions is empty") { tools.batch("""[{"id": "T-1", "decisions": []}]""") }
        failsWith("entry 1: decisions must be a JSON array") { tools.batch("""[{"id": "T-1", "decisions": {"question": "q", "answer": "a"}}]""") }
        failsWith("decisions cannot be set on a new item") { tools.batch("""[{"title": "new", "decisions": [{"question": "q", "answer": "a"}]}]""") }
        assertEquals(before, onDisk())
    }

    @Test
    fun `effort is set, changed, taken away and listed`() {
        val made = JsonParser.parseString(tools.create("a", null, null, null, null, null, null, effort = "S")).asJsonObject
        assertEquals("s", made.get("effort").asString)
        tools.create("b", null, null, null, null, null, null)
        tools.update("T-2", null, null, null, null, null, effort = "l")
        assertEquals(Effort.L, store.find("T-2")!!.effort)
        tools.update("T-2", "renamed", null, null, null, null)
        assertEquals(Effort.L, store.find("T-2")!!.effort)
        val ids = { out: String -> JsonParser.parseString(out).asJsonArray.map { it.asJsonObject.get("id").asString } }
        assertEquals(listOf("T-1"), ids(tools.list(effort = "s")))
        val rows = JsonParser.parseString(tools.list(compact = true)).asJsonArray.map { it.asJsonObject }
        assertEquals("l", rows[1].get("effort").asString)
        tools.update("T-2", null, null, null, null, null, effort = "")
        assertNull(store.find("T-2")!!.effort)
        failsWith("effort") { tools.create("c", null, null, null, null, null, null, effort = "xl") }
        failsWith("effort") { tools.update("T-1", null, null, null, null, null, effort = "huge") }
        failsWith("effort") { tools.list(effort = "tiny") }
    }

    @Test
    fun `batch sets effort on new and changed items`() {
        store.create(Draft("a"))
        tools.batch("""[{"id": "T-1", "effort": "m"}, {"title": "b", "effort": "s"}]""")
        assertEquals(Effort.M, store.find("T-1")!!.effort)
        assertEquals(Effort.S, store.find("T-2")!!.effort)
    }

    @Test
    fun `a tool that changes one item answers in one line that names what changed`() {
        val path = write("short.txt")
        val made = tools.create("a", "long details", "p1", "perf", path, 2, 3, effort = "s")
        assertEquals(1, made.lines().size)
        val row = JsonParser.parseString(made).asJsonObject
        assertEquals(setOf("id", "title", "priority", "effort", "status", "tags", "at"), row.keySet())
        assertEquals("$path:2-3", row.get("at").asString)

        val updated = tools.update("T-1", "b", null, null, null, "fixed", path, 4, null, resolution = "Done.")
        assertEquals(1, updated.lines().size)
        val after = JsonParser.parseString(updated).asJsonObject
        assertEquals(setOf("id", "title", "priority", "effort", "status", "tags", "at", "changed"), after.keySet())
        assertEquals("b", after.get("title").asString)
        assertEquals("fixed", after.get("status").asString)
        assertEquals("$path:4", after.get("at").asString)
        assertEquals(setOf("title", "status", "resolution", "anchors"), after.getAsJsonArray("changed").map { it.asString }.toSet())
        // Passing what is there already changes nothing, and the answer says so.
        assertEquals(0, JsonParser.parseString(tools.update("T-1", "b", null, null, null, null)).asJsonObject.getAsJsonArray("changed").size())

        val commented = JsonParser.parseString(tools.comment("T-1", "a note")).asJsonObject
        assertEquals(listOf("comments"), commented.getAsJsonArray("changed").map { it.asString })
        assertFalse(commented.has("comments"))
        val decided = JsonParser.parseString(tools.decided("T-1", """[{"question": "q", "answer": "a"}]""")).asJsonObject
        assertEquals(listOf("decisions"), decided.getAsJsonArray("changed").map { it.asString })
        assertFalse(decided.has("details"))

        // The line T-1 is attached to is taken out of the file. As it is fixed, it stays where it was.
        write(path, "one\ntwo\nthree\n")
        assertFalse(JsonParser.parseString(tools.comment("T-1", "gone")).asJsonObject.has("lost"))
        assertTrue(JsonParser.parseString(tools.update("T-1", null, null, null, null, "open")).asJsonObject.get("lost").asBoolean)
    }

    @Test
    fun `full gives the whole item back`() {
        val path = write("full.txt")
        val made = JsonParser.parseString(tools.create("a", "the details", null, null, path, 2, 2, full = true)).asJsonObject
        assertEquals("the details", made.get("details").asString)
        assertEquals("two", made.getAsJsonArray("anchors")[0].asJsonObject.get("text").asString)
        val updated = JsonParser.parseString(tools.update("T-1", "b", null, null, null, null, full = true)).asJsonObject
        assertEquals("the details", updated.get("details").asString)
        assertFalse(updated.has("changed"))
        assertEquals(1, JsonParser.parseString(tools.comment("T-1", "note", full = true)).asJsonObject.getAsJsonArray("comments").size())
        val decided = JsonParser.parseString(tools.decided("T-1", """[{"question": "q", "answer": "a"}]""", full = true)).asJsonObject
        assertEquals(1, decided.getAsJsonArray("decisions").size())
    }

    @Test
    fun `a place passed again finds a lost anchor`() {
        val a = write("lost1.txt")
        val b = write("lost2.txt")
        tools.create("x", null, null, null, a, 2, 2, "$b:1")
        fun lose() = store.update("T-1") { it.copy(anchors = listOf(it.anchors[0].copy(text = "was two", lost = true), it.anchors[1])) }
        val kept = lose().anchors[1]

        val row = JsonParser.parseString(tools.update("T-1", null, null, null, null, null, places = "$a:2, $b:1")).asJsonObject
        assertFalse(row.has("lost"))
        assertEquals(listOf("anchors"), row.getAsJsonArray("changed").map { it.asString })
        val found = store.find("T-1")!!.anchors
        assertFalse(found[0].lost)
        assertEquals("two", found[0].text)
        assertEquals(kept, found[1])

        lose()
        tools.update("T-1", null, null, null, null, null, a, 2, null)
        assertFalse(store.find("T-1")!!.anchors[0].lost)
        assertEquals("two", store.find("T-1")!!.anchors[0].text)
        assertEquals(kept, store.find("T-1")!!.anchors[1])

        lose()
        tools.batch("""[{"id": "T-1", "places": "$a:2, $b:1"}]""")
        assertFalse(store.find("T-1")!!.anyLost)
    }

    @Test
    fun `batch re-attaches items as todo_update does`() {
        val a = write("re1.txt")
        val b = write("re2.txt")
        tools.create("one place", null, null, null, a, 1, 1)
        tools.create("two places", null, null, null, a, 2, 2, "$b:1")
        tools.create("whole file", null, null, null, a, null, null)
        tools.create("note", null, null, null, null, null, null)
        var writes = 0
        val listener = { writes++; Unit }
        store.addListener(listener)
        val rows = JsonParser.parseString(
            tools.batch(
                """[{"id": "T-1", "startLine": 3, "endLine": 4, "tags": "moved"},
                    {"id": "T-2", "path": "$b", "startLine": 2},
                    {"id": "T-3", "places": ["$b:4", "$a"]},
                    {"id": "T-4", "path": "$b", "status": "fixed"}]"""
            )
        ).asJsonArray.map { it.asJsonObject }
        store.removeListener(listener)
        assertEquals(1, writes)
        assertEquals("$a:3-4", rows[0].get("at").asString)
        assertEquals("three\nfour", store.find("T-1")!!.anchor!!.text)
        assertEquals(listOf("moved"), store.find("T-1")!!.tags)
        assertEquals(listOf("$a:2", "$b:2"), store.find("T-2")!!.anchors.map { it.place })
        assertEquals(listOf("$b:4", a), store.find("T-3")!!.anchors.map { it.place })
        assertEquals(listOf(b), store.find("T-4")!!.anchors.map { it.place })
        assertEquals(Status.FIXED, store.find("T-4")!!.status)
        tools.batch("""[{"id": "T-3", "places": ""}]""")
        assertTrue(store.find("T-3")!!.anchors.isEmpty())

        val before = onDisk()
        failsWith("entry 2: lines 9-9") { tools.batch("""[{"id": "T-1", "startLine": 1}, {"id": "T-4", "startLine": 9}]""") }
        failsWith("entry 2: file not found") { tools.batch("""[{"id": "T-1", "title": "new"}, {"id": "T-1", "path": "no/such.txt"}]""") }
        failsWith("not both") { tools.batch("""[{"id": "T-1", "startLine": 1, "places": "$a:1"}]""") }
        failsWith("2 places") { tools.batch("""[{"id": "T-2", "startLine": 1}]""") }
        failsWith("places") { tools.batch("""[{"id": "T-2", "path": "other.txt", "startLine": 1}]""") }
        failsWith("startLine must be a whole number") { tools.batch("""[{"id": "T-1", "startLine": "3"}]""") }
        assertEquals(before, onDisk())
    }

    @Test
    fun `new items of a batch name each other by their refs`() {
        store.create(Draft("there already"))
        val d = "$"
        val rows = JsonParser.parseString(
            tools.batch(
                """[{"title": "whole", "ref": "whole"},
                    {"title": "first part", "ref": "first", "parent": "${d}whole"},
                    {"title": "second part", "parent": "${d}whole", "blockedBy": "${d}first, T-1"},
                    {"title": "said twice", "ref": "again-2", "duplicateOf": "${d}first", "blockedBy": ["${d}whole"]},
                    {"id": "T-1", "blockedBy": "${d}again-2", "parent": "${d}whole"}]"""
            )
        ).asJsonArray.map { it.asJsonObject }
        assertEquals(listOf("T-2", "T-3", "T-4", "T-5", "T-1"), rows.map { it.get("id").asString })
        assertFalse(rows[0].has("ref"))
        assertEquals("T-2", store.find("T-3")!!.parent)
        assertEquals("T-2", store.find("T-4")!!.parent)
        assertEquals(listOf("T-3", "T-1"), store.find("T-4")!!.blockedBy)
        assertEquals("T-3", store.find("T-5")!!.duplicateOf)
        assertEquals(listOf("T-2"), store.find("T-5")!!.blockedBy)
        assertEquals(listOf("T-5"), store.find("T-1")!!.blockedBy)
        assertEquals("T-2", store.find("T-1")!!.parent)

        val before = onDisk()
        failsWith("entry 2: there is no new item with ref \"hole\"") {
            tools.batch("""[{"title": "w", "ref": "whole"}, {"title": "p", "parent": "${d}hole"}]""")
        }
        // A ref names an entry before the one that uses it.
        failsWith("entry 1: there is no new item with ref \"later\"") {
            tools.batch("""[{"title": "p", "blockedBy": "${d}later"}, {"title": "w", "ref": "later"}]""")
        }
        failsWith("entry 1: there is no new item with ref \"x\"") { tools.batch("""[{"id": "T-1", "duplicateOf": "${d}x"}]""") }
        failsWith("entry 2: ref \"w\" is already") { tools.batch("""[{"title": "a", "ref": "w"}, {"title": "b", "ref": "w"}]""") }
        failsWith("entry 1: ref must be a name") { tools.batch("""[{"title": "a", "ref": "two words"}]""") }
        failsWith("entry 1: ref is for a new item") { tools.batch("""[{"id": "T-1", "ref": "one"}]""") }
        failsWith("entry 2") { tools.batch("""[{"title": "a", "ref": "a", "parent": "T-2"}, {"title": "deep", "parent": "${d}a"}]""") }
        assertEquals(before, onDisk())
    }

    @Test
    fun `the commit that names a closed item is shown as the one that fixed it`() {
        store.create(Draft("named in a commit"))
        store.create(Draft("named and still open"))
        store.create(Draft("says so itself"))
        store.create(Draft("never named"))
        TempRepo.commit(repo, "Cache the path\n\nStarts T-1 and T-2.")
        TempRepo.commit(repo, "Fix T-1, T-3: keep the path\n\nNot T-10.")
        val head = TempRepo.git(repo, "rev-parse", "--short", "HEAD").trim()
        tools.batch(
            """[{"id": "T-1", "status": "fixed"}, {"id": "T-3", "status": "done", "fixedIn": "abc1234"}, {"id": "T-4", "status": "fixed"}]"""
        )
        fun fixedIn(id: String) = JsonParser.parseString(tools.get(id)).asJsonObject.get("fixedIn")?.asString
        assertEquals(head, fixedIn("T-1"))
        assertNull(fixedIn("T-2"))
        assertEquals("abc1234", fixedIn("T-3"))
        assertNull(fixedIn("T-4"))
        val listed = JsonParser.parseString(tools.list()).asJsonArray.map { it.asJsonObject.get("fixedIn")?.asString }
        assertEquals(listOf(head, null, "abc1234", null), listed)
        assertEquals(head, JsonParser.parseString(tools.update("T-1", "renamed", null, null, null, null, full = true)).asJsonObject.get("fixedIn").asString)
        // It is read from git each time and never written to the file.
        assertNull(store.find("T-1")!!.fixedIn)
        assertFalse(onDisk().contains(head))

        TempRepo.commit(repo, "T-4 at last")
        assertEquals(TempRepo.git(repo, "rev-parse", "--short", "HEAD").trim(), fixedIn("T-4"))
        tools.update("T-1", null, null, null, null, "open")
        assertNull(fixedIn("T-1"))

        // A name is stored as the commit it stands for at the time; what git does not know is stored as it is.
        tools.update("T-2", null, null, null, null, "fixed", fixedIn = "HEAD~1")
        assertEquals(head, store.find("T-2")!!.fixedIn)
        tools.batch("""[{"id": "T-2", "fixedIn": " HEAD "}, {"id": "T-3", "fixedIn": "no-such-branch"}]""")
        assertEquals(TempRepo.git(repo, "rev-parse", "--short", "HEAD").trim(), store.find("T-2")!!.fixedIn)
        assertEquals("no-such-branch", store.find("T-3")!!.fixedIn)
        tools.update("T-3", null, null, null, null, null, fixedIn = "abc1234")
        assertEquals("abc1234", store.find("T-3")!!.fixedIn)

        // source is stored the same way: a name, and each end of a range, as the commit it stands for at the time.
        val newest = TempRepo.git(repo, "rev-parse", "--short", "HEAD").trim()
        val branch = TempRepo.git(repo, "rev-parse", "--abbrev-ref", "HEAD").trim()
        tools.create("left behind", null, null, null, null, null, null, source = "HEAD~1..HEAD")
        assertEquals("$head..$newest", store.find("T-5")!!.source)
        val row = JsonParser.parseString(tools.update("T-5", null, null, null, null, null, source = branch)).asJsonObject
        assertEquals(newest, row.get("source").asString)
        assertEquals(newest, store.find("T-5")!!.source)
        tools.batch(
            """[{"id": "T-5", "source": "abc1234..HEAD"}, {"title": "in words", "source": "the nightly run of 7 October"},
                |{"title": "one end unknown", "source": "no-such-branch..HEAD~1"}, {"title": "an id", "source": "30c6f6ae..e9ac6245"},
                |{"title": "three dots", "source": "HEAD~1...HEAD"}]""".trimMargin()
        )
        assertEquals("abc1234..$newest", store.find("T-5")!!.source)
        assertEquals("the nightly run of 7 October", store.find("T-6")!!.source)
        assertEquals("no-such-branch..$head", store.find("T-7")!!.source)
        assertEquals("30c6f6ae..e9ac6245", store.find("T-8")!!.source)
        assertEquals("$head...$newest", store.find("T-9")!!.source)
        tools.update("T-5", null, null, null, null, null, source = "")
        assertNull(store.find("T-5")!!.source)
    }

    @Test
    fun `decided takes the needs-decision tag away, and toDecide puts it back`() {
        tools.create("pick a license", null, null, "legal", null, null, null, toDecide = "MIT or Apache?")
        val row = JsonParser.parseString(tools.decided("T-1", """[{"question": "Which license?", "answer": "MIT"}]""")).asJsonObject
        assertEquals(listOf("legal"), row.getAsJsonArray("tags").map { it.asString })
        assertEquals(setOf("tags", "decisions"), row.getAsJsonArray("changed").map { it.asString }.toSet())
        val item = store.find("T-1")!!
        assertEquals("MIT or Apache?", item.toDecide)
        assertEquals(1, item.decisions.size)
        // The work is then closed without the tags being passed again.
        tools.update("T-1", null, null, null, null, "fixed")
        assertEquals(listOf("legal"), store.find("T-1")!!.tags)
        // One question of several is answered: the item goes on waiting.
        tools.update("T-1", null, null, null, null, "open", toDecide = "And which year?")
        assertTrue(store.find("T-1")!!.needsDecision)
        assertEquals(1, store.find("T-1")!!.decisions.size)
    }

    @Test
    fun `get gives the code of an anchor once, and the stored text of one that is lost`() {
        val path = write("once.txt")
        tools.create("a", null, null, null, path, 2, 3, "$path:4, $path")
        // The line of the second anchor is taken out of the file.
        write(path, "one\ntwo\nthree\n")
        val anchors = JsonParser.parseString(tools.get("T-1")).asJsonObject.getAsJsonArray("anchors").map { it.asJsonObject }
        assertEquals(setOf("path", "startLine", "endLine", "lost", "code"), anchors[0].keySet())
        assertEquals("two\nthree", anchors[0].get("code").asString)
        assertEquals(setOf("path", "startLine", "endLine", "lost", "text"), anchors[1].keySet())
        assertEquals("four", anchors[1].get("text").asString)
        assertEquals(setOf("path", "lost"), anchors[2].keySet())

        val full = JsonParser.parseString(tools.get("T-1", full = true)).asJsonObject.getAsJsonArray("anchors").map { it.asJsonObject }
        assertEquals(setOf("path", "startLine", "endLine", "text", "before", "after", "lost", "code"), full[0].keySet())
        assertEquals(listOf("one"), full[0].getAsJsonArray("before").map { it.asString })
    }

    @Test
    fun `list finds items that have any of several words or phrases`() {
        tools.create("Cache the index", null, null, "perf", null, null, null)
        tools.create("Rework it", "the lookup table is slow", null, null, null, null, null)
        tools.create("Memoise lookups", null, null, "perf", null, null, null)
        tools.create("Unrelated", "nothing of the kind", null, null, null, null, null)
        fun ids(json: String) = JsonParser.parseString(json).asJsonArray.map { it.asJsonObject.get("id").asString }

        assertEquals(listOf("T-1", "T-2", "T-3"), ids(tools.list(anyText = "cache, MEMOISE \"lookup table\"", compact = true)))
        assertEquals(listOf("T-2"), ids(tools.list(anyText = "\"lookup table\" \"index cache\"")))
        assertEquals("[]", tools.list(anyText = "memo-table lookup-cache", compact = true))
        // With the other filters, all of which must match.
        assertEquals(listOf("T-1", "T-3"), ids(tools.list(tags = "perf", anyText = "cache lookup")))
        assertEquals(listOf("T-2"), ids(tools.list(text = "slow", anyText = "cache lookup")))
        assertEquals(4, ids(tools.list(anyText = " ")).size)
    }

    @Test
    fun `delete removes an item the agent made and nothing else`() {
        tools.create("made to try the tools", null, null, null, null, null, null)
        store.create(Draft("the user's own"))
        tools.create("whole", null, null, null, null, null, null)
        tools.create("part", null, null, null, null, null, null, parent = "T-3")
        tools.create("blocker", null, null, null, null, null, null)
        tools.create("original", null, null, null, null, null, null)
        store.update("T-2") { it.copy(blockedBy = listOf("T-5"), duplicateOf = "T-6") }

        val row = JsonParser.parseString(tools.delete("t-1")).asJsonObject
        assertEquals("T-1", row.get("deleted").asString)
        assertEquals("made to try the tools", row.get("title").asString)
        assertNull(store.find("T-1"))
        failsWith("the user") { tools.delete("T-2") }
        failsWith("T-4") { tools.delete("T-3") }
        failsWith("blocked by") { tools.delete("T-5") }
        failsWith("duplicate of") { tools.delete("T-6") }
        failsWith("T-9") { tools.delete("T-9") }
        failsWith("not an item id") { tools.delete("nine") }
        assertEquals(5, store.items.size)
        // A part goes, and then the whole it was the only part of.
        tools.delete("T-4")
        tools.delete("T-3")
        assertEquals(listOf("T-2", "T-5", "T-6"), store.items.map { it.id })
        // The next item does not take the id of one that is gone.
        tools.create("next", null, null, null, null, null, null)
        assertNotNull(store.find("T-7"))
    }

    @Test
    fun `the one line shows what the short fields that changed became`() {
        tools.create("a", null, null, null, null, null, null)
        tools.create("b", null, null, null, null, null, null)
        tools.update("T-2", null, null, null, null, "done")
        val row = JsonParser.parseString(
            tools.update(
                "T-1", null, "new details", "p1", "perf", "fixed", blockedBy = "T-2", source = "run 7", fixedIn = "abc1234",
                resolution = "Done.", effort = "s",
            )
        ).asJsonObject
        assertEquals(
            setOf("details", "priority", "tags", "status", "blockedBy", "source", "fixedIn", "resolution", "effort"),
            row.getAsJsonArray("changed").map { it.asString }.toSet(),
        )
        assertEquals("abc1234", row.get("fixedIn").asString)
        assertEquals("run 7", row.get("source").asString)
        // As saved, the blocker that is closed with it.
        assertEquals("T-2", row.get("blockedBy").asString)
        assertEquals("s", row.get("effort").asString)
        assertEquals("p1", row.get("priority").asString)
        assertFalse(row.has("details"))
        assertFalse(row.has("resolution"))

        // What did not change is not repeated, and what was taken away is shown as gone.
        val next = JsonParser.parseString(tools.update("T-1", null, null, null, null, null, blockedBy = "", source = "", effort = "", parent = "T-2")).asJsonObject
        assertFalse(next.has("fixedIn"))
        assertTrue(next.get("source").isJsonNull)
        assertTrue(next.get("effort").isJsonNull)
        assertTrue(next.get("blockedBy").isJsonNull)
        assertEquals("T-2", next.get("parent").asString)
        val last = JsonParser.parseString(tools.update("T-1", null, null, null, null, null, parent = "", duplicateOf = "T-2")).asJsonObject
        assertTrue(last.get("parent").isJsonNull)
        assertEquals("T-2", last.get("duplicateOf").asString)
    }

    @Test
    fun `every answer that says lost sees a file rewritten or deleted outside the IDE`() {
        write("rewritten-outside.txt")
        write("deleted-outside.txt")
        tools.create("first", null, null, null, "rewritten-outside.txt", 2, 2)
        tools.create("second", null, null, null, "rewritten-outside.txt", 3, 3)
        tools.create("third", null, null, null, "deleted-outside.txt", 2, 2)
        write("rewritten-outside.txt", "nothing\nof what was\nhere is left, three aside\nthree\n")
        val rows = JsonParser.parseString(tools.list(compact = true)).asJsonArray.map { it.asJsonObject }
        assertTrue(rows[0].get("lost").asBoolean)
        // What only moved is found where it is now.
        assertFalse(rows[1].has("lost"))
        assertEquals("rewritten-outside.txt:4", rows[1].get("at").asString)

        write("rewritten-outside.txt", "all of it gone, and longer than before so that the size differs too\n")
        assertTrue(JsonParser.parseString(tools.comment("T-2", "a note")).asJsonObject.get("lost").asBoolean)
        Files.delete(repo.resolve("deleted-outside.txt"))
        val third = JsonParser.parseString(tools.get("T-3")).asJsonObject.getAsJsonArray("anchors")[0].asJsonObject
        assertTrue(third.get("lost").asBoolean)
        assertEquals("two", third.get("text").asString)
        assertEquals(listOf("T-1", "T-2", "T-3"), JsonParser.parseString(tools.list(lost = true)).asJsonArray.map { it.asJsonObject.get("id").asString })
    }

    @Test
    fun `a path outside the checkout is refused`() {
        Files.writeString(tmp.resolve("secret.txt"), "secret\n")
        failsWith("not found") { tools.create("x", null, null, null, "../secret.txt", 1, 1) }
        failsWith("not found") { tools.create("x", null, null, null, tmp.resolve("secret.txt").toString(), 1, 1) }
        assertEquals(0, store.items.size)
    }

    @Test
    fun `a qualified id reaches an item of another project`() {
        val other = TempRepo.create(tmp.resolve("infra"), "git@github.com:me/infra.git")
        stores.ensure(stores.resolve(other))
        stores.items("infra").create(Draft("over there"))
        assertEquals("over there", JsonParser.parseString(tools.get("infra/T-1")).asJsonObject.get("title").asString)
        tools.create("blocked here", null, null, null, null, null, null, blockedBy = "infra/T-1")
        assertEquals(listOf("infra/T-1"), store.find("T-1")!!.blockedBy)
        tools.update("infra/T-1", null, null, null, null, "done")
        assertEquals(Status.DONE, stores.items("infra").find("T-1")!!.status)
    }

    @Test
    fun `create in a folder with no project makes one and says so`() {
        val fresh = TempRepo.create(tmp.resolve("fresh"), "git@github.com:me/fresh.git")
        val reply = TodoTools(Workspace(stores, fresh)).create("first", null, null, null, null, null, null)
        assertTrue(reply.contains("created project \\\"fresh\\\"") || reply.contains("created project \"fresh\""))
        assertEquals("first", stores.items("fresh").find("T-1")!!.title)
    }

    @Test
    fun `create in the home folder files into inbox`() {
        TodoTools(Workspace(stores, home)).create("an idea", null, null, null, null, null, null)
        assertEquals("an idea", stores.items("inbox").find("T-1")!!.title)
        assertNull(stores.project("home"))
    }

    @Test
    fun `get without a checkout gives the stored text and says the code was not read`() {
        val path = write("a.txt")
        tools.create("anchored", null, null, null, path, 2, 2)
        repo.toFile().deleteRecursively()
        val json = JsonParser.parseString(TodoTools(Workspace(stores, home)).get("demo/T-1")).asJsonObject
        assertEquals("two", json.getAsJsonArray("anchors")[0].asJsonObject.get("text").asString)
        assertTrue(json.get("codeNotRead").asString.contains("github.com/me/demo"))
    }

    @Test
    fun `a project given as a folder that is not there is refused`() {
        failsWith("no such folder") { tools.create("x", null, null, null, null, null, null, project = "../no-such-folder") }
        assertEquals(listOf("demo"), stores.projects().map { it.key })
    }

    @Test
    fun `an item made in the home folder is found there again`() {
        val inHome = TodoTools(Workspace(stores, home))
        inHome.create("an idea", null, null, null, null, null, null)
        inHome.update("T-1", "a better idea", null, null, null, null)
        assertEquals("a better idea", JsonParser.parseString(inHome.get("T-1")).asJsonObject.get("title").asString)
        // The home folder has no project, so its list is every project's, the inbox included.
        assertEquals("inbox/T-1", JsonParser.parseString(inHome.list()).asJsonArray[0].asJsonObject.get("id").asString)
    }

    @Test
    fun `a broken project of another repository does not stop making a project`() {
        Files.createDirectories(tmp.resolve("store/projects/other"))
        Files.writeString(tmp.resolve("store/projects/other/project.json"), "{ broken")
        val fresh = TempRepo.create(tmp.resolve("fresh"), "git@github.com:me/fresh.git")
        TodoTools(Workspace(stores, fresh)).create("first", null, null, null, null, null, null)
        assertEquals("first", stores.items("fresh").find("T-1")!!.title)
    }

    @Test
    fun `a link that leads out of the checkout is refused`() {
        Files.writeString(tmp.resolve("secret.txt"), "secret\n")
        Files.createSymbolicLink(repo.resolve("link.txt"), tmp.resolve("secret.txt"))
        failsWith("not found") { tools.create("x", null, null, null, "link.txt", 1, 1) }
        assertEquals(0, store.items.size)
    }

    @Test
    fun `an absolute path that leads inside the checkout is refused`() {
        write("a.txt")
        failsWith("not found") { tools.create("x", null, null, null, repo.resolve("a.txt").toString(), 1, 1) }
        assertEquals(0, store.items.size)
    }

    @Test
    fun `a path is stored relative to the checkout with its dots worked out`() {
        write("a.txt")
        tools.create("x", null, null, null, "x/../a.txt", 2, 2)
        assertEquals("a.txt", store.find("T-1")!!.anchor!!.path)
    }

    @Test
    fun `a store that fails while the project is found is told as a tool error`() {
        Files.createDirectories(tmp.resolve("store/projects/inbox"))
        Files.writeString(tmp.resolve("store/projects/inbox/project.json"), "{ broken")
        failsWith("project.json") { TodoTools(Workspace(stores, home)).create("x", null, null, null, null, null, null) }
    }

    @Test
    fun `a repository whose items are still in its items json is left to the plugin until they are imported`() {
        val old = TempRepo.create(tmp.resolve("old"), "git@github.com:me/old.git")
        val file = old.resolve(".todos/items.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, """{"version":2,"nextId":2,"items":[{"id":"T-1","title":"kept by the plugin"}]}""")
        val workspace = Workspace(stores, old)
        val inOld = TodoTools(workspace)
        val still = "the items of $old are still in .todos/items.json: " +
            "open the project once in the IDE with Not My TODO 0.3.0 or newer, which moves them into the store"
        failsWith(still) { inOld.list() }
        failsWith(still) { inOld.get("T-1") }
        failsWith(still) { inOld.create("x", null, null, null, null, null, null) }
        failsWith(still) { inOld.batch("""[{"title": "x"}]""") }
        failsWith(still) { tools.move("T-1", old.toString()) }
        assertNull(stores.project("old"))
        assertTrue(Instructions.of(workspace).contains("still in .todos/items.json: open the project once in the IDE"))
        assertEquals(0, JsonParser.parseString(inOld.list(compact = true, project = "*")).asJsonArray.size())
        tools.create("here", null, null, null, null, null, null)
        assertEquals("here", JsonParser.parseString(inOld.get("demo/T-1")).asJsonObject.get("title").asString)

        val key = stores.ensure(stores.resolve(old)).record.key
        assertEquals(ImportResult.Imported(1), Import.run(stores, key, file))
        assertEquals("T-2", JsonParser.parseString(inOld.create("next", null, null, null, null, null, null)).asJsonObject.get("id").asString)
    }

    @Test
    fun `a first create or batch with bad input makes no project`() {
        val fresh = TempRepo.create(tmp.resolve("fresh"), "git@github.com:me/fresh.git")
        val inFresh = TodoTools(Workspace(stores, fresh))
        failsWith("title") { inFresh.create("  ", null, null, null, null, null, null) }
        failsWith("path") { inFresh.create("x", null, null, null, null, 2, null) }
        failsWith("startLine") { inFresh.create("x", null, null, null, "a.txt", null, 2) }
        failsWith("a.txt:two") { inFresh.create("x", null, null, null, null, null, null, "a.txt:two") }
        failsWith("priority") { inFresh.create("x", null, "urgent", null, null, null, null) }
        assertNull(stores.project("fresh"))
        failsWith("entry 2: title") { inFresh.batch("""[{"title": "x"}, {"title": " "}]""") }
        failsWith("entry 1: unknown field colour") { inFresh.batch("""[{"title": "x", "colour": "red"}]""") }
        failsWith("no project yet") { inFresh.batch("""[{"id": "T-1", "tags": "x"}]""") }
        assertNull(stores.project("fresh"))
        val reply = JsonParser.parseString(inFresh.create("first", null, null, null, null, null, null)).asJsonObject
        assertEquals("created project \"fresh\" for $fresh", reply.get("notice").asString)
    }
}

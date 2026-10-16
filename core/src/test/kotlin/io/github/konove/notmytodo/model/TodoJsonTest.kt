package io.github.konove.notmytodo.model

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoJsonTest {
    private fun item(n: Int, anchor: Anchor? = null) = TodoItem(
        id = ItemId.of(n), title = "title $n", details = "d", priority = Priority.P1,
        tags = listOf("perf"), status = Status.IN_PROGRESS, author = Author.AGENT,
        created = "2026-10-05T14:02:11Z", updated = "2026-10-05T15:00:00Z", anchors = listOfNotNull(anchor),
    )

    @Test
    fun `round trip keeps every field`() {
        val anchor = Anchor("src/a.cpp", 3, 4, "x\ny", listOf("b"), listOf("a1", "a2"), lost = true)
        val file = TodoFile(2, 8, listOf(item(1), item(7, anchor)))
        assertEquals(file, TodoJson.decode(TodoJson.encode(file)))
    }

    @Test
    fun `ids sort by number not by text`() {
        val file = TodoFile(2, 101, listOf(item(100), item(9), item(10)))
        val ids = TodoJson.decode(TodoJson.encode(file)).items.map { it.id }
        assertEquals(listOf("T-9", "T-10", "T-100"), ids)
    }

    @Test
    fun `id parsing accepts only unpadded positive numbers`() {
        assertEquals(12, ItemId.parse("T-12"))
        assertNull(ItemId.parse("T-012"))
        assertNull(ItemId.parse("T-0"))
        assertNull(ItemId.parse("t-1"))
        assertNull(ItemId.parse("T-"))
    }

    @Test
    fun `keys are written in a fixed order`() {
        val text = TodoJson.encode(TodoFile(2, 2, listOf(item(1))))
        val order = listOf("\"version\"", "\"nextId\"", "\"items\"", "\"id\"", "\"title\"", "\"details\"",
            "\"priority\"", "\"tags\"", "\"status\"", "\"author\"", "\"created\"", "\"updated\"")
        val positions = order.map { text.indexOf(it) }
        assertTrue(positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
        assertTrue(text.endsWith("\n"))
        assertTrue(text.contains("\"priority\": \"p1\""))
        assertTrue(text.contains("\"status\": \"in_progress\""))
    }

    @Test
    fun `blank text decodes to an empty file`() {
        assertEquals(TodoFile(), TodoJson.decode(""))
        assertEquals(TodoFile(), TodoJson.decode("  \n"))
    }

    @Test
    fun `missing optional fields get defaults`() {
        val file = TodoJson.decode("""{"items":[{"id":"T-4","title":"x"}]}""")
        val it = file.items.single()
        assertEquals(Priority.P2, it.priority)
        assertEquals(Status.OPEN, it.status)
        assertEquals(Author.USER, it.author)
        assertEquals(emptyList<String>(), it.tags)
        assertNull(it.anchor)
        assertEquals(5, file.nextId)
    }

    @Test
    fun `nextId is raised above the highest id`() {
        val file = TodoJson.decode("""{"version":1,"nextId":2,"items":[{"id":"T-9","title":"x"}]}""")
        assertEquals(10, file.nextId)
    }

    @Test
    fun `invalid input is rejected with a reason`() {
        fun fails(text: String, part: String) {
            val e = assertThrows(TodoFormatException::class.java) { TodoJson.decode(text) }
            assertTrue("${e.message} should mention $part", e.message!!.contains(part))
        }
        fails("{", "JSON")
        fails("[]", "object")
        fails("""{"version":3}""", "version")
        fails("""{"items":[{"id":"T-01","title":"x"}]}""", "id")
        fails("""{"items":[{"id":"T-1","title":" "}]}""", "title")
        fails("""{"items":[{"id":"T-1","title":"x"},{"id":"T-1","title":"y"}]}""", "T-1")
        fails("""{"items":[{"id":"T-1","title":"x","priority":"p9"}]}""", "priority")
        fails("""{"items":[{"id":"T-1","title":"x","status":"later"}]}""", "status")
        fails("""{"items":[{"id":"T-1","title":"x","anchor":{"path":"a","startLine":3,"endLine":2}}]}""", "line")
    }

    @Test
    fun `fields this version does not know are written back`() {
        val text = """{"version":1,"nextId":2,"schema":"x","items":[{"id":"T-1","title":"x",
            "votes":[{"by":"me","text":"hm"}],"decision":null,
            "anchor":{"path":"a.c","startLine":1,"column":4}}]}"""
        val file = TodoJson.decode(text)
        assertEquals(setOf("votes", "decision"), file.items.single().unknown.keys)
        val written = JsonParser.parseString(TodoJson.encode(file)).asJsonObject
        val item = written.getAsJsonArray("items").single().asJsonObject
        assertEquals("x", written.get("schema").asString)
        assertEquals("hm", item.getAsJsonArray("votes").single().asJsonObject.get("text").asString)
        assertTrue(item.get("decision").isJsonNull)
        assertEquals(4, item.getAsJsonArray("anchors")[0].asJsonObject.get("column").asInt)
        assertEquals(file, TodoJson.decode(TodoJson.encode(file)))
    }

    @Test
    fun `a version 1 file is read and written as version 2`() {
        for (text in listOf("""{"items":[]}""", """{"version":1,"items":[]}""", """{"version":2,"items":[]}""")) {
            val file = TodoJson.decode(text)
            assertEquals(2, file.version)
            assertTrue(TodoJson.encode(file).contains("\"version\": 2"))
        }
        assertTrue(TodoJson.encode(TodoFile()).contains("\"version\": 2"))
    }

    @Test
    fun `tags are normalised`() {
        assertEquals(listOf("perf", "bug"), Tags.normalizeAll(listOf("#Perf", " bug ", "perf", "", "two words")))
        assertEquals(listOf("a", "b"), Tags.parseList("#a, b  #A"))
    }

    @Test
    fun `comments round trip in order, after updated`() {
        val comments = listOf(
            Comment(Author.AGENT, "2026-10-06T09:00:00Z", "found it"),
            Comment(Author.USER, "2026-10-06T09:05:00Z", "two\nlines"),
        )
        val file = TodoFile(2, 2, listOf(item(1).copy(comments = comments)))
        val text = TodoJson.encode(file)
        assertEquals(file, TodoJson.decode(text))
        assertTrue(text.indexOf("\"comments\"") > text.indexOf("\"updated\""))
        val written = JsonParser.parseString(text).asJsonObject.getAsJsonArray("items").single().asJsonObject
        val first = written.getAsJsonArray("comments")[0].asJsonObject
        assertEquals(listOf("author", "time", "text"), first.keySet().toList())
        assertEquals("agent", first.get("author").asString)
    }

    @Test
    fun `an item without comments has no comments key`() {
        assertTrue(!TodoJson.encode(TodoFile(2, 2, listOf(item(1)))).contains("comments"))
    }

    @Test
    fun `comments keep unknown fields and reject what is not a comment`() {
        val text = """{"items":[{"id":"T-1","title":"x","comments":[{"text":"hm","edited":true}]}]}"""
        val file = TodoJson.decode(text)
        val comment = file.items.single().comments.single()
        assertEquals(Author.USER, comment.author)
        assertEquals("hm", comment.text)
        assertEquals("", comment.time)
        assertTrue(TodoJson.encode(file).contains("\"edited\": true"))
        for (bad in listOf("""[3]""", """[{"author":"robot","text":"x"}]""")) {
            val e = assertThrows(TodoFormatException::class.java) {
                TodoJson.decode("""{"items":[{"id":"T-1","title":"x","comments":$bad}]}""")
            }
            assertTrue(e.message!!.contains("comment"))
        }
    }

    @Test
    fun `several anchors round trip, and one on a whole file is written without lines`() {
        val lines = Anchor("src/a.cpp", 3, 4, "x\ny", listOf("b"), listOf("a1"))
        val whole = Anchor.file("src/b.cpp").copy(lost = true)
        val file = TodoFile(2, 2, listOf(item(1).copy(anchors = listOf(lines, whole))))
        assertEquals(file, TodoJson.decode(TodoJson.encode(file)))
        val written = TodoJson.encodeItem(file.items.single())
        assertFalse(written.has("anchor"))
        val second = written.getAsJsonArray("anchors")[1].asJsonObject
        assertEquals(setOf("path", "lost"), second.keySet())
        assertTrue(TodoJson.decode(TodoJson.encode(file)).items.single().anchors[1].isFile)
    }

    @Test
    fun `the single anchor of an older file is read as the only one`() {
        val old = """{"version":1,"items":[{"id":"T-1","title":"x","anchor":{"path":"a.c","startLine":2,"endLine":3,"text":"t"}}]}"""
        val item = TodoJson.decode(old).items.single()
        assertEquals(listOf("a.c:2-3"), item.anchors.map { it.place })
        assertTrue(item.unknown.isEmpty())
        val both = """{"items":[{"id":"T-1","title":"x","anchor":{"path":"old.c","startLine":1},
            "anchors":[{"path":"a.c"},{"path":"b.c","startLine":5}]}]}"""
        assertEquals(listOf("a.c", "b.c:5"), TodoJson.decode(both).items.single().anchors.map { it.place })
        val e = assertThrows(TodoFormatException::class.java) { TodoJson.decode("""{"items":[{"id":"T-1","title":"x","anchors":[3]}]}""") }
        assertTrue(e.message, e.message!!.contains("anchor"))
    }

    @Test
    fun `links are kept, and left out of the file when an item has none`() {
        val linked = item(3).copy(blockedBy = listOf("T-1", "T-2"), duplicateOf = "T-1", parent = "T-2")
        val file = TodoFile(2, 4, listOf(item(1), item(2), linked))
        val text = TodoJson.encode(file)
        assertEquals(file, TodoJson.decode(text))
        assertEquals(1, Regex("\"blockedBy\"").findAll(text).count())
        assertEquals(1, Regex("\"duplicateOf\": \"T-1\"").findAll(text).count())
        assertEquals(1, Regex("\"parent\": \"T-2\"").findAll(text).count())
    }

    @Test
    fun `ids are read as they may be typed`() {
        assertEquals("T-7", ItemId.normalize(" t-7 "))
        assertEquals("T-7", ItemId.normalize("7"))
        assertNull(ItemId.normalize("T-07"))
        assertNull(ItemId.normalize("seven"))
        assertEquals(listOf("T-1", "T-2"), ItemId.parseList("T-1, 2 t-1"))
        assertEquals(emptyList<String>(), ItemId.parseList(" "))
        assertThrows(IllegalArgumentException::class.java) { ItemId.parseList("T-1 x") }
    }

    @Test
    fun `an item is blocked while a blocker that is there is not closed`() {
        val items = listOf(
            item(1), item(2).copy(status = Status.DONE), item(3).copy(status = Status.FIXED),
            item(4).copy(blockedBy = listOf("T-1", "T-2")), item(5).copy(blockedBy = listOf("T-2", "T-9")),
            item(6).copy(blockedBy = listOf("T-3"), parent = "T-1"), item(7).copy(parent = "T-1"),
        )
        val links = Links(items)
        assertEquals(listOf("T-1"), links.openBlockers(items[3]).map { it.id })
        assertFalse(links.isBlocked(items[4]))
        assertTrue(links.isBlocked(items[5]))
        assertEquals(listOf("T-6", "T-7"), links.children("T-1").map { it.id })
        assertEquals(emptyList<TodoItem>(), links.children("T-2"))
    }

    @Test
    fun `where an item came from and how it was fixed are kept, and left out when not said`() {
        val fixed = item(2).copy(source = "30c6f6ae..e9ac6245", fixedIn = "93e7970", resolution = "Cached the path.")
        val file = TodoFile(2, 3, listOf(item(1), fixed))
        val text = TodoJson.encode(file)
        assertEquals(file, TodoJson.decode(text))
        assertEquals(3, Regex("\"source\"|\"fixedIn\"|\"resolution\"").findAll(text).count())
        val blank = TodoJson.decode("""{"items":[{"id":"T-1","title":"a","source":" ","fixedIn":""}]}""").items[0]
        assertNull(blank.source)
        assertNull(blank.fixedIn)
    }

    @Test
    fun `what is to be decided and what was decided survive a round trip`() {
        val decided = Decision("Per unit or per cell?", listOf("Per unit", "Per cell"), "Per cell", "2026-10-06T09:00:00Z")
        val file = TodoFile(2, 2, listOf(item(1).copy(toDecide = "How paths are shared", decisions = listOf(decided))))
        val text = TodoJson.encode(file)
        assertEquals(file, TodoJson.decode(text))
        assertTrue(text.contains("\"toDecide\": \"How paths are shared\""))
        assertFalse(TodoJson.encode(TodoFile(2, 2, listOf(item(1)))).contains("ecide"))
    }

    @Test
    fun `a decision keeps its unknown fields, and what is not a decision is rejected`() {
        val text = """{"items":[{"id":"T-1","title":"a","toDecide":" ","decisions":[
            {"question":"q","options":["x"],"answer":"x","time":"t","by":"phone"},{"question":"r","answer":"y"}]}]}"""
        val file = TodoJson.decode(text)
        val item = file.items.single()
        assertNull(item.toDecide)
        assertEquals(setOf("by"), item.decisions[0].unknown.keys)
        assertEquals(emptyList<String>(), item.decisions[1].options)
        assertTrue(item.unknown.isEmpty())
        assertTrue(TodoJson.encode(file).contains("\"by\": \"phone\""))
        assertThrows(TodoFormatException::class.java) {
            TodoJson.decode("""{"items":[{"id":"T-1","title":"a","decisions":["x"]}]}""")
        }
    }

    @Test
    fun `effort is kept, left out when not said, and refused when it is not s, m or l`() {
        val file = TodoFile(2, 3, listOf(item(1), item(2).copy(effort = Effort.M)))
        val text = TodoJson.encode(file)
        assertEquals(file, TodoJson.decode(text))
        assertEquals(1, Regex("\"effort\": \"m\"").findAll(text).count())
        assertEquals(1, Regex("\"effort\"").findAll(text).count())
        assertThrows(TodoFormatException::class.java) {
            TodoJson.decode("""{"items":[{"id":"T-1","title":"a","effort":"xl"}]}""")
        }
    }
}

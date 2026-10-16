package io.github.konove.notmytodo.store

import io.github.konove.notmytodo.model.Anchor
import io.github.konove.notmytodo.model.TodoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MoveTest {
    private val tmp = Files.createTempDirectory("nmt-move")
    private val store = Store(tmp.resolve("store"), home = tmp.resolve("home")).also { s ->
        s.inbox()
        listOf("web", "infra").forEach { s.dir(it).writeRecord(ProjectRecord(it)) }
    }

    @Test
    fun `an item moved from the inbox gets the next id there and says where it came from`() {
        store.items("web").create(Draft("existing"))
        val note = store.items("inbox").create(Draft("idea", anchors = listOf(Anchor.file("a.txt"))))
        val moved = store.move("inbox", note.id, "web")
        assertEquals(mapOf("T-1" to "T-2"), moved.ids)
        assertEquals(1, moved.anchorsDropped)
        val there = store.items("web").find("T-2")!!
        assertEquals("inbox/T-1", there.movedFrom)
        assertEquals(emptyList<Anchor>(), there.anchors)
        assertNull(store.items("inbox").find("T-1"))
        assertEquals("T-3", store.items("web").create(Draft("after")).id)
    }

    @Test
    fun `links to a moved item are rewritten in every project`() {
        store.items("inbox").create(Draft("a"))
        store.items("inbox").create(Draft("b", blockedBy = listOf("T-1")))
        store.items("infra").create(Draft("c", blockedBy = listOf("inbox/T-1")))
        store.items("web").create(Draft("d", duplicateOf = "inbox/T-1"))
        store.move("inbox", "T-1", "web")
        assertEquals(listOf("web/T-2"), store.items("inbox").find("T-2")!!.blockedBy)
        assertEquals(listOf("web/T-2"), store.items("infra").find("T-1")!!.blockedBy)
        assertEquals("T-2", store.items("web").find("T-1")!!.duplicateOf)
    }

    @Test
    fun `a moved item's links to what stays behind are qualified`() {
        store.items("inbox").create(Draft("stays"))
        store.items("inbox").create(Draft("goes", blockedBy = listOf("T-1")))
        store.move("inbox", "T-2", "web")
        assertEquals(listOf("inbox/T-1"), store.items("web").find("T-1")!!.blockedBy)
    }

    @Test
    fun `parts move only with their parent, and only when asked`() {
        val inbox = store.items("inbox")
        inbox.create(Draft("whole"))
        inbox.create(Draft("part", parent = "T-1"))
        assertTrue(assertThrows(StoreException::class.java) { store.move("inbox", "T-1", "web") }.message!!.contains("withParts"))
        assertTrue(assertThrows(StoreException::class.java) { store.move("inbox", "T-2", "web") }.message!!.contains("part of T-1"))
        val moved = store.move("inbox", "T-1", "web", withParts = true)
        assertEquals(mapOf("T-1" to "T-1", "T-2" to "T-2"), moved.ids)
        assertEquals("T-1", store.items("web").find("T-2")!!.parent)
        assertEquals(emptyList<TodoItem>(), store.items("inbox").items)
    }

    @Test
    fun `renaming a project rewrites the ids that name it`() {
        store.items("infra").create(Draft("x"))
        store.items("web").create(Draft("y", blockedBy = listOf("infra/T-1")))
        store.rename("infra", "platform")
        assertNull(store.project("infra"))
        assertEquals("platform", store.project("platform")!!.key)
        assertEquals(listOf("platform/T-1"), store.items("web").find("T-1")!!.blockedBy)
        assertEquals("x", store.items("platform").find("T-1")!!.title)
    }

    @Test
    fun `a rename to a bad or taken key is refused`() {
        assertThrows(StoreException::class.java) { store.rename("infra", "Bad Key!") }
        assertThrows(StoreException::class.java) { store.rename("infra", "web") }
        assertThrows(StoreException::class.java) { store.rename("infra", "inbox") }
        assertThrows(StoreException::class.java) { store.rename("inbox", "elsewhere") }
        val newer = store.dir("old2").path
        Files.createDirectories(newer)
        Files.writeString(newer.resolve("project.json"), """{"version":2,"key":"old2"}""")
        assertThrows(StoreException::class.java) { store.rename("old2", "new2") }
    }
}

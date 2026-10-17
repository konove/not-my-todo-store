package io.github.konove.notmytodo.store

import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.TodoItem
import io.github.konove.notmytodo.model.TodoJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class ImportTest {
    private val tmp = Files.createTempDirectory("nmt-import")
    private val store = Store(tmp.resolve("store"), home = tmp.resolve("home")).also { it.dir("demo").writeRecord(ProjectRecord("demo")) }
    private val file = tmp.resolve("repo/.todos/items.json").also {
        Files.createDirectories(it.parent)
        Files.writeString(
            it,
            """{"version":2,"nextId":9,"items":[{"id":"T-2","title":"two","status":"fixed"},{"id":"T-5","title":"five","blockedBy":["T-2"]}]}""",
        )
    }

    @Test
    fun `items keep their ids and the next id, and the file is left alone`() {
        val before = Files.readString(file)
        assertEquals(ImportResult.Imported(2), Import.run(store, "demo", file))
        val items = store.items("demo")
        assertEquals(listOf("T-2", "T-5"), items.items.map { it.id })
        assertEquals(listOf("T-2"), items.find("T-5")!!.blockedBy)
        assertEquals("T-9", items.create(Draft("next")).id)
        assertEquals(before, Files.readString(file))
        assertEquals(file.toAbsolutePath().normalize().toString(), store.project("demo")!!.importedFrom!!.path)
    }

    @Test
    fun `statuses the IDE held are written in place of the file's`() {
        Import.run(store, "demo", file, mapOf("T-2" to Status.DONE))
        assertEquals(Status.DONE, store.items("demo").find("T-2")!!.status)
    }

    @Test
    fun `a file is imported once`() {
        Import.run(store, "demo", file)
        store.items("demo").delete("T-5")
        assertEquals(ImportResult.AlreadyImported, Import.run(store, "demo", file))
        assertNull(store.items("demo").find("T-5"))
    }

    @Test
    fun `an id that is already there stops the import before anything is written`() {
        store.items("demo").create(Draft("one"))
        store.items("demo").create(Draft("two"))
        assertEquals(ImportResult.Clash(listOf("T-2")), Import.run(store, "demo", file))
        assertEquals(listOf("T-1", "T-2"), store.items("demo").items.map { it.id })
        assertNull(store.project("demo")!!.importedFrom)
    }

    @Test
    fun `an id held by an item file that cannot be read is a clash too, and the file is left as it is`() {
        val items = store.dir("demo").path.resolve("items")
        Files.createDirectories(items)
        Files.writeString(items.resolve("T-5.json"), "{ hand-edited")
        assertEquals(ImportResult.Clash(listOf("T-5")), Import.run(store, "demo", file))
        assertEquals("{ hand-edited", Files.readString(items.resolve("T-5.json")))
        assertNull(store.project("demo")!!.importedFrom)
    }

    @Test
    fun `an import that stopped before it recorded the file is finished when run again`() {
        // What a first run leaves when it stops between writing the items and recording the file.
        store.items("demo").batch { b -> TodoJson.decode(Files.readString(file)).items.forEach(b::put) }
        assertEquals(ImportResult.Imported(2), Import.run(store, "demo", file))
        assertEquals(listOf("T-2", "T-5"), store.items("demo").items.map { it.id })
        assertEquals(file.toAbsolutePath().normalize().toString(), store.project("demo")!!.importedFrom!!.path)
        assertEquals(Import.sha256(Files.readAllBytes(file)), store.project("demo")!!.importedFrom!!.sha256)
    }

    @Test
    fun `an item already there that differs from the file's is still a clash`() {
        store.items("demo").batch { b -> b.put(TodoItem(id = "T-2", title = "another two")) }
        assertEquals(ImportResult.Clash(listOf("T-2")), Import.run(store, "demo", file))
        assertNull(store.project("demo")!!.importedFrom)
    }

    @Test
    fun `adjust changes each item before it is written`() {
        Import.run(store, "demo", file, adjust = { it.copy(title = it.title.uppercase()) })
        assertEquals(listOf("TWO", "FIVE"), store.items("demo").items.map { it.title })
    }

    @Test
    fun `with renumber the items get the project's next ids, and their links follow them`() {
        store.items("demo").create(Draft("one"))
        store.items("demo").create(Draft("two"))
        assertEquals(ImportResult.Imported(2), Import.run(store, "demo", file, renumber = true))
        val items = store.items("demo")
        assertEquals(listOf("T-1", "T-2", "T-3", "T-4"), items.items.map { it.id })
        assertEquals("two", items.find("T-3")!!.title)
        assertEquals(listOf("T-3"), items.find("T-4")!!.blockedBy)
        assertEquals("T-5", items.create(Draft("next")).id)
        assertEquals(file.toAbsolutePath().normalize().toString(), store.project("demo")!!.importedFrom!!.path)
    }

    @Test
    fun `with renumber a link to an id the file does not hold is kept as it is`() {
        Files.writeString(file, """{"version":2,"nextId":3,"items":[{"id":"T-1","title":"one","blockedBy":["T-40"],"parent":"T-2"},{"id":"T-2","title":"two"}]}""")
        store.items("demo").create(Draft("there"))
        Import.run(store, "demo", file, renumber = true)
        val one = store.items("demo").find("T-2")!!
        assertEquals("one", one.title)
        assertEquals(listOf("T-40"), one.blockedBy)
        assertEquals("T-3", one.parent)
    }

    @Test
    fun `with renumber the held statuses and adjust apply by the file's own ids`() {
        store.items("demo").create(Draft("one"))
        Import.run(store, "demo", file, mapOf("T-2" to Status.DONE), adjust = { it.copy(title = it.id + " " + it.title) }, renumber = true)
        val moved = store.items("demo").find("T-2")!!
        assertEquals(Status.DONE, moved.status)
        assertEquals("T-2 two", moved.title)
    }
}

package io.github.konove.notmytodo.store

import io.github.konove.notmytodo.model.TodoFormatException
import io.github.konove.notmytodo.model.TodoItem
import io.github.konove.notmytodo.model.TodoJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ProjectDirTest {
    private val dir = ProjectDir(Files.createTempDirectory("nmt-dir").resolve("projects").resolve("demo"))
    private fun file(name: String) = dir.path.resolve("items").resolve(name)

    @Test
    fun `a missing project reads as nothing`() {
        val s = dir.read()
        assertNull(s.record)
        assertEquals(emptyList<TodoItem>(), s.items)
        assertEquals("demo", dir.key)
    }

    @Test
    fun `write puts each changed item in a file of its own and removes the gone ones`() {
        val a = TodoItem("T-1", "a")
        val b = TodoItem("T-2", "b")
        dir.write(ProjectRecord("demo", nextId = 3), emptyList(), listOf(a, b))
        assertEquals(listOf(a, b), dir.read().items)
        val untouched = Files.getLastModifiedTime(file("T-1.json"))
        Thread.sleep(20)
        dir.write(null, listOf(a, b), listOf(a))
        assertEquals(untouched, Files.getLastModifiedTime(file("T-1.json")))
        assertFalse(Files.exists(file("T-2.json")))
        assertEquals(3, dir.read().record!!.nextId)
        assertEquals(listOf("T-1.json"), Files.list(dir.path.resolve("items")).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `a file that cannot be read is named and the others are read`() {
        dir.write(ProjectRecord("demo", nextId = 3), emptyList(), listOf(TodoItem("T-1", "a")))
        Files.writeString(file("T-2.json"), "{ nope")
        Files.writeString(file("T-3.json"), TodoJson.encodeItemText(TodoItem("T-4", "misnamed")))
        val s = dir.read()
        assertEquals(listOf("T-1"), s.items.map { it.id })
        assertEquals(listOf("T-2.json", "T-3.json"), s.unreadable.map { it.file })
        assertTrue(s.unreadable[1].problem.contains("T-4"))
    }

    @Test
    fun `a bad project file is an error`() {
        Files.createDirectories(dir.path)
        Files.writeString(dir.path.resolve("project.json"), "[]")
        assertThrows(TodoFormatException::class.java) { dir.read() }
    }

    @Test
    fun `the stamp changes with every write and not otherwise`() {
        val before = dir.stamp()
        dir.write(ProjectRecord("demo", nextId = 2), emptyList(), listOf(TodoItem("T-1", "a")))
        val after = dir.stamp()
        assertNotEquals(before, after)
        assertEquals(after, dir.stamp())
        dir.write(null, listOf(TodoItem("T-1", "a")), listOf(TodoItem("T-1", "b")))
        assertNotEquals(after, dir.stamp())
    }

    @Test
    fun `a write that fails leaves no temporary file behind`() {
        val a = TodoItem("T-1", "a")
        dir.write(ProjectRecord("demo", nextId = 2), emptyList(), listOf(a))
        Files.createDirectories(file("T-2.json"))
        Files.writeString(file("T-2.json").resolve("blocker"), "x")
        assertThrows(StoreException::class.java) {
            dir.write(ProjectRecord("demo", nextId = 3), listOf(a), listOf(a, TodoItem("T-2", "b")))
        }
        assertEquals(emptyList<String>(), tempFiles(dir.path))
        assertEquals(emptyList<String>(), tempFiles(dir.path.resolve("items")))
    }

    private fun tempFiles(folder: java.nio.file.Path): List<String> =
        Files.list(folder).use { s -> s.map { it.fileName.toString() }.filter { it.endsWith(".tmp") }.toList() }

    @Test
    fun `unknown fields of the project file are kept`() {
        Files.createDirectories(dir.path)
        Files.writeString(dir.path.resolve("project.json"), """{"version":1,"key":"demo","nextId":4,"colour":"red"}""")
        val record = dir.read().record!!
        dir.writeRecord(record.copy(nextId = 5))
        assertTrue(Files.readString(dir.path.resolve("project.json")).contains("\"colour\": \"red\""))
        assertEquals(5, dir.read().record!!.nextId)
    }

    @Test
    fun `an item's file is named after its id in the items folder`() {
        val dir = ProjectDir(Files.createTempDirectory("nmt-dir").resolve("demo"))
        assertEquals(dir.path.resolve("items").resolve("T-3.json"), dir.itemFile("T-3"))
    }
}

package io.github.konove.notmytodo.store

import io.github.konove.notmytodo.model.TodoFormatException
import io.github.konove.notmytodo.model.TodoItem
import io.github.konove.notmytodo.model.TodoJson
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/**
 * The files of one project: `project.json` and `items/T-n.json`, one per item. A file is only ever
 * replaced by renaming a whole new one over it, so a reader never sees half of one. Taking the
 * store's lock is the caller's business.
 */
class ProjectDir(val path: Path) {
    val key: String get() = path.fileName.toString()
    private val itemsDir: Path get() = path.resolve("items")
    private val recordFile: Path get() = path.resolve("project.json")

    /** The file item [id] is kept in. */
    fun itemFile(id: String): Path = itemsDir.resolve("$id.json")

    /** An item file that could not be read, and why. It is left as it is. */
    data class Unreadable(val file: String, val problem: String)

    data class Snapshot(val record: ProjectRecord?, val items: List<TodoItem>, val unreadable: List<Unreadable>)

    /**
     * Changes whenever a file of the project is written, added or removed. The file key is in it
     * because a rename puts a new file in place, which two writes in the same instant would not show.
     */
    fun stamp(): String = (listOf(recordFile) + itemFiles()).mapNotNull { file ->
        try {
            val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
            "${file.fileName}:${attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS)}:${attributes.size()}:${attributes.fileKey()}"
        } catch (_: NoSuchFileException) {
            null
        }
    }.joinToString("|")

    /**
     * What project.json says; null when there is none. Readers take no lock, so a file a writer
     * removed after it was found counts as none. Throws [TodoFormatException] when it is not valid.
     */
    fun readRecord(): ProjectRecord? {
        if (!Files.isRegularFile(recordFile)) return null
        val text = try {
            Files.readString(recordFile)
        } catch (_: NoSuchFileException) {
            return null
        }
        return ProjectJson.decode(text, key)
    }

    fun read(): Snapshot {
        val record = readRecord()
        val items = ArrayList<TodoItem>()
        val unreadable = ArrayList<Unreadable>()
        for (file in itemFiles()) {
            val name = file.fileName.toString()
            try {
                val item = TodoJson.decodeItemText(Files.readString(file), name)
                if (name == "${item.id}.json") items += item
                else unreadable += Unreadable(name, "holds item ${item.id}, not ${name.removeSuffix(".json")}")
            } catch (_: NoSuchFileException) {
                // Removed by a writer after it was listed: it is gone, not broken.
            } catch (e: TodoFormatException) {
                unreadable += Unreadable(name, e.message ?: "not valid")
            } catch (e: IOException) {
                unreadable += Unreadable(name, "cannot be read: ${e.message}")
            }
        }
        return Snapshot(record, items.sortedBy { it.number }, unreadable)
    }

    fun writeRecord(record: ProjectRecord) = guarded {
        val temp = temp(recordFile, ProjectJson.encode(record))
        try {
            move(temp, recordFile)
        } finally {
            discard(temp)
        }
    }

    /**
     * Writes the items of [after] that differ from [before], removes those that [before] has and
     * [after] has not, and then writes [record] unless it is null. Every new file is written before
     * the first is renamed into place. If anything fails, the temporary files not yet renamed are
     * removed, so the folder keeps only what was there before.
     */
    fun write(record: ProjectRecord?, before: List<TodoItem>, after: List<TodoItem>) = guarded {
        val old = before.associateBy { it.id }
        val changed = after.filter { old[it.id] != it }
        val gone = old.keys - after.map { it.id }.toSet()
        Files.createDirectories(itemsDir)
        val pending = ArrayList<Pair<Path, Path>>()
        var recordTemp: Path? = null
        try {
            changed.forEach { item ->
                val target = itemFile(item.id)
                pending += temp(target, TodoJson.encodeItemText(item)) to target
            }
            recordTemp = record?.let { temp(recordFile, ProjectJson.encode(it)) }
            while (pending.isNotEmpty()) {
                val (temp, target) = pending.first()
                move(temp, target)
                pending.removeAt(0)
            }
            gone.forEach { Files.deleteIfExists(itemsDir.resolve("$it.json")) }
            recordTemp?.let {
                move(it, recordFile)
                recordTemp = null
            }
        } finally {
            pending.forEach { (temp, _) -> discard(temp) }
            recordTemp?.let(::discard)
        }
    }

    private fun itemFiles(): List<Path> =
        if (!Files.isDirectory(itemsDir)) emptyList()
        else Files.list(itemsDir).use { s -> s.filter { it.fileName.toString().endsWith(".json") && Files.isRegularFile(it) }.sorted().toList() }

    private fun temp(target: Path, text: String): Path {
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, target.fileName.toString() + ".", ".tmp")
        try {
            Files.writeString(temp, text)
        } catch (e: IOException) {
            discard(temp)
            throw e
        }
        return temp
    }

    /**
     * Removes a temporary file that was not renamed into place. The failure being reported is the
     * write's own, so a failure to remove the leftover is not raised over it.
     */
    private fun discard(temp: Path) {
        try {
            Files.deleteIfExists(temp)
        } catch (_: IOException) {
        }
    }

    private fun move(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            throw StoreException("$path cannot be written: ${e.message}")
        }
    }
}

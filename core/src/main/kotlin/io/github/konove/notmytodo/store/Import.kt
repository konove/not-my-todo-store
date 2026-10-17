package io.github.konove.notmytodo.store

import io.github.konove.notmytodo.model.ItemId
import io.github.konove.notmytodo.model.ItemRef
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.TodoFormatException
import io.github.konove.notmytodo.model.TodoItem
import io.github.konove.notmytodo.model.TodoJson
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

sealed interface ImportResult {
    data class Imported(val count: Int) : ImportResult

    data object AlreadyImported : ImportResult

    /** Ids the project already has, or holds in an item file that cannot be read: nothing was written. */
    data class Clash(val ids: List<String>) : ImportResult
}

/**
 * Brings the items of an `items.json` kept in a repository into a project of the store, once per
 * file. The file itself is never written.
 */
object Import {
    /**
     * Imports [file] into project [key], which must exist. [statuses] go in place of what the file
     * says, by item id: the statuses the IDE showed and had not yet written. [adjust] changes each
     * item before it is written. An item the project already has exactly as it would be written
     * was put there by a run that stopped before it recorded the file: it is left as it is, and
     * this run finishes that one. With [renumber] the items get the project's next ids in the file's
     * order, and the links among them follow; a link to an id the file does not hold is kept. Nothing
     * can clash then.
     */
    fun run(
        store: Store,
        key: String,
        file: Path,
        statuses: Map<String, Status> = emptyMap(),
        adjust: (TodoItem) -> TodoItem = { it },
        renumber: Boolean = false,
    ): ImportResult = store.lock.withLock {
        val path = file.toAbsolutePath().normalize().toString()
        if (store.projects().any { it.importedFrom?.path == path }) return@withLock ImportResult.AlreadyImported
        if (store.project(key) == null) throw StoreException("there is no project $key")
        val bytes = try {
            Files.readAllBytes(file)
        } catch (e: IOException) {
            throw StoreException("$file cannot be read: ${e.message}")
        }
        val read = try {
            TodoJson.decode(String(bytes, Charsets.UTF_8))
        } catch (e: TodoFormatException) {
            throw StoreException("$file cannot be imported: ${e.message}")
        }
        val target = store.items(key)
        val incoming = read.items.map { item -> adjust(statuses[item.id]?.let { item.copy(status = it) } ?: item) }
        if (renumber) {
            // Ids are renamed before the clash check, so that a file whose ids the project holds is taken whole.
            // A ref that is not a bare id of this file (another project's item, or text that is no id) is left alone.
            val first = target.nextId
            val ids = incoming.mapIndexed { i, item -> item.id to ItemId.of(first + i) }.toMap()
            fun moved(ref: String): String = if (ItemRef.parse(ref)?.project == null) ids[ref] ?: ref else ref
            val renamed = incoming.map { item ->
                item.copy(
                    id = ids.getValue(item.id),
                    blockedBy = item.blockedBy.map(::moved),
                    duplicateOf = item.duplicateOf?.let(::moved),
                    parent = item.parent?.let(::moved),
                )
            }
            target.batch { b -> renamed.forEach(b::put) }
            store.updateRecord(key) { it.copy(importedFrom = Imported(path, sha256(bytes))) }
            return@withLock ImportResult.Imported(read.items.size)
        }
        // An id held by an item file that cannot be read is taken too: put would refuse it mid-batch.
        val clash = incoming.filter { item ->
            val there = target.find(item.id)
            (there != null && there != item) || target.unreadable.any { it.file == "${item.id}.json" }
        }.map { it.id }
        if (clash.isNotEmpty()) return@withLock ImportResult.Clash(clash)
        val missing = incoming.filter { target.find(it.id) == null }
        target.batch { b ->
            missing.forEach(b::put)
            b.reserve(read.nextId)
        }
        store.updateRecord(key) { it.copy(importedFrom = Imported(path, sha256(bytes))) }
        ImportResult.Imported(read.items.size)
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

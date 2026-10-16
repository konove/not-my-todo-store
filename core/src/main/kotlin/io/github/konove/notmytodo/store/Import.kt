package io.github.konove.notmytodo.store

import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.TodoFormatException
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
     * says, by item id: the statuses the IDE showed and had not yet written.
     */
    fun run(store: Store, key: String, file: Path, statuses: Map<String, Status> = emptyMap()): ImportResult = store.lock.withLock {
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
        // An id held by an item file that cannot be read is taken too: put would refuse it mid-batch.
        val clash = read.items.map { it.id }.filter { id ->
            target.find(id) != null || target.unreadable.any { it.file == "$id.json" }
        }
        if (clash.isNotEmpty()) return@withLock ImportResult.Clash(clash)
        target.batch { b ->
            read.items.forEach { item -> b.put(statuses[item.id]?.let { item.copy(status = it) } ?: item) }
            b.reserve(read.nextId)
        }
        store.updateRecord(key) { it.copy(importedFrom = Imported(path, sha256(bytes))) }
        ImportResult.Imported(read.items.size)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

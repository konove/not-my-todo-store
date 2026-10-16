package io.github.konove.notmytodo.store

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * The one lock every writer of a store takes: the IDE and each server process. Between processes it
 * is an OS lock on [file], which the OS lets go of when a process dies. The OS lock belongs to the
 * process, so within one the threads queue on a [ReentrantLock] shared by every instance on the same
 * file. A thread that holds it may take it again.
 */
class StoreLock(file: Path, private val timeoutMs: Long = 5000) {
    private val file: Path = file.toAbsolutePath().normalize()
    private val shared: Shared = SHARED.computeIfAbsent(this.file) { Shared() }

    private class Shared {
        val threads = ReentrantLock()
        var channel: FileChannel? = null
        var held: FileLock? = null
    }

    fun <T> withLock(block: () -> T): T {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        if (!shared.threads.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) throw locked()
        try {
            val outermost = shared.threads.holdCount == 1
            if (outermost) acquire(deadline)
            try {
                return block()
            } finally {
                if (outermost) release()
            }
        } finally {
            shared.threads.unlock()
        }
    }

    private fun acquire(deadline: Long) {
        val channel = try {
            Files.createDirectories(file.parent)
            FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        } catch (e: IOException) {
            throw StoreException("$file cannot be opened: ${e.message}")
        }
        while (true) {
            val lock = try {
                channel.tryLock()
            } catch (e: IOException) {
                channel.close()
                throw StoreException("$file cannot be locked: ${e.message}")
            }
            if (lock != null) {
                shared.channel = channel
                shared.held = lock
                return
            }
            if (System.nanoTime() >= deadline) {
                channel.close()
                throw locked()
            }
            Thread.sleep(POLL_MS)
        }
    }

    private fun release() {
        try {
            shared.held?.release()
        } catch (_: IOException) {
        }
        try {
            shared.channel?.close()
        } catch (_: IOException) {
        }
        shared.held = null
        shared.channel = null
    }

    private fun locked() = StoreException("the store is locked by another writer; try again ($file)")

    private companion object {
        const val POLL_MS = 20L
        val SHARED = ConcurrentHashMap<Path, Shared>()
    }
}

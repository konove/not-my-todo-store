package io.github.konove.notmytodo.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class StoreLockTest {
    private val file = Files.createTempDirectory("nmt-lock").resolve("store.lock")

    @Test
    fun `a thread that holds the lock may take it again`() {
        assertEquals(2, StoreLock(file).withLock { StoreLock(file).withLock { 2 } })
    }

    @Test
    fun `threads take turns`() {
        val lock = StoreLock(file)
        var inside = 0
        var most = 0
        val threads = (1..4).map {
            Thread {
                repeat(50) {
                    lock.withLock {
                        inside++
                        most = maxOf(most, inside)
                        Thread.yield()
                        inside--
                    }
                }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertEquals(1, most)
    }

    @Test
    fun `a lock held by another process times out with a message, and is free once that process ends`() {
        val holder = Jvm.start("io.github.konove.notmytodo.store.LockHolderKt", file.toString(), "1500")
        assertEquals("held", holder.inputStream.bufferedReader().readLine())
        val e = assertThrows(StoreException::class.java) { StoreLock(file, timeoutMs = 200).withLock { } }
        assertTrue(e.message!!.contains("locked by another writer"))
        assertTrue(holder.waitFor(10, TimeUnit.SECONDS))
        assertEquals("free", StoreLock(file, timeoutMs = 200).withLock { "free" })
    }
}

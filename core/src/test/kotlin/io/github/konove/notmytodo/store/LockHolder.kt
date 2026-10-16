package io.github.konove.notmytodo.store

import java.nio.file.Path

/** Holds the lock at args[0] for args[1] milliseconds, and says "held" once it has it. */
fun main(args: Array<String>) {
    StoreLock(Path.of(args[0])).withLock {
        println("held")
        System.out.flush()
        Thread.sleep(args[1].toLong())
    }
}

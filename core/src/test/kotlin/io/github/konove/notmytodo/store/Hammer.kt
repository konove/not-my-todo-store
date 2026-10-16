package io.github.konove.notmytodo.store

import java.nio.file.Path

/** Creates args[1] items titled "args[2] n" in project demo of the store at args[0]. */
fun main(args: Array<String>) {
    val root = Path.of(args[0])
    val store = ItemStore(ProjectDir(root.resolve("projects").resolve("demo")), StoreLock(root.resolve("store.lock")))
    repeat(args[1].toInt()) { store.create(Draft("${args[2]} $it")) }
}

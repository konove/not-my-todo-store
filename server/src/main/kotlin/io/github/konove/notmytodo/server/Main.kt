package io.github.konove.notmytodo.server

import io.github.konove.notmytodo.store.Store
import io.github.konove.notmytodo.store.StoreRoot
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.file.Path

const val SERVER_NAME = "notmytodo-store"
const val SERVER_VERSION = "0.1.0"

fun server(workspace: Workspace): McpServer =
    McpServer(SERVER_NAME, SERVER_VERSION, { Instructions.of(workspace) }, TodoToolSpecs.all(TodoTools(workspace)))

fun main() {
    // Standard output carries the protocol; anything else printed there would break it.
    val protocol = PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8)
    System.setOut(System.err)
    val workspace = Workspace(Store(StoreRoot.default()), Path.of("").toAbsolutePath())
    server(workspace).serve(System.`in`, protocol)
}

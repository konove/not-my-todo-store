package io.github.konove.notmytodo.store

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

class StoreRootTest {
    private val home = Path.of("/home/u")

    @Test
    fun `the environment variable wins`() {
        assertEquals(Path.of("/data/todo"), StoreRoot.default(mapOf("NOT_MY_TODO_STORE" to "/data/todo"), "Linux", home))
    }

    @Test
    fun `linux uses XDG_DATA_HOME or the local share folder`() {
        assertEquals(Path.of("/x/not-my-todo-store"), StoreRoot.default(mapOf("XDG_DATA_HOME" to "/x"), "Linux", home))
        assertEquals(Path.of("/home/u/.local/share/not-my-todo-store"), StoreRoot.default(emptyMap(), "Linux", home))
    }

    @Test
    fun `macOS uses Application Support`() {
        assertEquals(Path.of("/home/u/Library/Application Support/not-my-todo-store"), StoreRoot.default(emptyMap(), "Mac OS X", home))
    }

    @Test
    fun `windows uses LOCALAPPDATA`() {
        assertEquals(Path.of("/l/not-my-todo-store"), StoreRoot.default(mapOf("LOCALAPPDATA" to "/l"), "Windows 11", home))
    }
}

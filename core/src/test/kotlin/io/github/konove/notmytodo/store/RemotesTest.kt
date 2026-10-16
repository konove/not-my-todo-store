package io.github.konove.notmytodo.store

import org.junit.Assert.assertEquals
import org.junit.Test

class RemotesTest {
    @Test
    fun `the ssh, https and scp forms of one repository are the same`() {
        listOf(
            "git@github.com:konove/not-my-todo.git",
            "https://github.com/konove/not-my-todo",
            "https://user@GitHub.com/konove/not-my-todo.git/",
            "ssh://git@github.com:22/konove/not-my-todo.git",
            "github.com:konove/not-my-todo",
        ).forEach { assertEquals(it, "github.com/konove/not-my-todo", Remotes.normalize(it)) }
    }

    @Test
    fun `a local path stays a path`() {
        assertEquals("/srv/repos/a", Remotes.normalize("/srv/repos/a.git"))
        assertEquals("file:///srv/a", Remotes.normalize("file:///srv/a.git"))
        assertEquals("C:\\repos\\a", Remotes.normalize("C:\\repos\\a"))
    }
}

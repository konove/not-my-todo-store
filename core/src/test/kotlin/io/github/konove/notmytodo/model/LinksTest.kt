package io.github.konove.notmytodo.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {
    @Test
    fun `a blocker in another project counts while it is open`() {
        val other = mapOf(
            "infra/T-3" to TodoItem("T-3", "elsewhere"),
            "infra/T-4" to TodoItem("T-4", "closed", status = Status.DONE),
        )
        val a = TodoItem("T-1", "a", blockedBy = listOf("infra/T-3"))
        val b = TodoItem("T-2", "b", blockedBy = listOf("infra/T-4", "infra/T-9"))
        val links = Links(listOf(a, b)) { other[it] }
        assertTrue(links.isBlocked(a))
        assertFalse(links.isBlocked(b))
        assertEquals(listOf("infra/T-3"), links.openBlockerIds(a))
    }
}

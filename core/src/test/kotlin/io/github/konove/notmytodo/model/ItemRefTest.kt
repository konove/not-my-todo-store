package io.github.konove.notmytodo.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemRefTest {
    @Test
    fun `bare and qualified ids are read as they may be typed`() {
        assertEquals(ItemRef(null, "T-7"), ItemRef.parse("t-7"))
        assertEquals(ItemRef(null, "T-7"), ItemRef.parse(" 7 "))
        assertEquals(ItemRef("infra", "T-3"), ItemRef.parse("Infra/T-3"))
        assertEquals(ItemRef("my.repo-2", "T-3"), ItemRef.parse("my.repo-2/3"))
        assertNull(ItemRef.parse("infra/"))
        assertNull(ItemRef.parse("/T-3"))
        assertNull(ItemRef.parse("in fra/T-3"))
        assertNull(ItemRef.parse("T-0"))
        assertEquals("infra/T-3", ItemRef.normalize("INFRA/3"))
    }

    @Test
    fun `a ref is stored bare in its own project and qualified elsewhere`() {
        assertEquals("T-3", ItemRef("infra", "T-3").storedFrom("infra"))
        assertEquals("infra/T-3", ItemRef("infra", "T-3").storedFrom("web"))
        assertEquals("T-3", ItemRef(null, "T-3").storedFrom("web"))
    }

    @Test
    fun `parseList names what is not an id`() {
        assertEquals(listOf(ItemRef(null, "T-1"), ItemRef("a", "T-2")), ItemRef.parseList("T-1, a/T-2 T-1"))
        val e = assertThrows(IllegalArgumentException::class.java) { ItemRef.parseList("T-1 x/y") }
        assertTrue(e.message!!.contains("x/y"))
    }
}

package io.github.konove.notmytodo.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnchorResolverTest {
    private val text = listOf("a", "b", "    target();", "    more();", "c", "d", "e").joinToString("\n")
    private fun anchor() = AnchorResolver.capture("f.txt", text, 3, 4)

    @Test
    fun `capture records text and up to three neighbours`() {
        val a = anchor()
        assertEquals("    target();\n    more();", a.text)
        assertEquals(listOf("a", "b"), a.before)
        assertEquals(listOf("c", "d", "e"), a.after)
        assertEquals(3, a.startLine)
        assertEquals(4, a.endLine)
    }

    @Test
    fun `capture at file edges`() {
        val first = AnchorResolver.capture("f.txt", text, 1, 1)
        assertEquals(emptyList<String>(), first.before)
        val last = AnchorResolver.capture("f.txt", text, 7, 7)
        assertEquals(emptyList<String>(), last.after)
        val clamped = AnchorResolver.capture("f.txt", text, 6, 99)
        assertEquals(6, clamped.startLine)
        assertEquals(7, clamped.endLine)
        val empty = AnchorResolver.capture("f.txt", "", 5, 9)
        assertEquals(1, empty.startLine)
        assertEquals(1, empty.endLine)
        assertEquals("", empty.text)
    }

    @Test
    fun `unchanged text stays where it is`() {
        assertEquals(LineRange(3, 4), AnchorResolver.resolve(anchor(), text))
    }

    @Test
    fun `text moved down or up is found`() {
        assertEquals(LineRange(5, 6), AnchorResolver.resolve(anchor(), "x\ny\n$text"))
        assertEquals(LineRange(2, 3), AnchorResolver.resolve(anchor(), text.removePrefix("a\n")))
    }

    @Test
    fun `re-indented text is found`() {
        val changed = text.replace("    target();", "\ttarget();").replace("    more();", "\tmore();   ")
        assertEquals(LineRange(4, 5), AnchorResolver.resolve(anchor(), "top\n$changed"))
    }

    @Test
    fun `rewritten or deleted text is not found`() {
        assertNull(AnchorResolver.resolve(anchor(), text.replace("target()", "other()")))
        assertNull(AnchorResolver.resolve(anchor(), "a\nb\nc\nd\ne"))
    }

    @Test
    fun `duplicate text is resolved by its neighbours`() {
        val file = "x\n}\ny\nbefore\n}\nafter\n"
        val a = AnchorResolver.capture("f.txt", file, 5, 5)
        val moved = "new\nnew\n$file"
        assertEquals(LineRange(7, 7), AnchorResolver.resolve(a, moved))
    }

    @Test
    fun `duplicate text with no telling neighbours is not found`() {
        val a = AnchorResolver.capture("f.txt", "p\n}\nq", 2, 2)
        assertNull(AnchorResolver.resolve(a, "zero\none\n}\ntwo\nthree\n}\nfour"))
    }

    @Test
    fun `all-blank anchor text is only found in place`() {
        val a = AnchorResolver.capture("f.txt", "a\n\nb", 2, 2)
        assertEquals(LineRange(2, 2), AnchorResolver.resolve(a, "a\n\nb"))
        assertNull(AnchorResolver.resolve(a, "new\na\n\nb\n\n"))
    }

    @Test
    fun `CRLF text resolves like LF text`() {
        val crlf = "x\r\n" + text.replace("\n", "\r\n")
        assertEquals(LineRange(4, 5), AnchorResolver.resolve(anchor(), crlf))
        assertEquals(anchor().text, AnchorResolver.capture("f.txt", text.replace("\n", "\r\n"), 3, 4).text)
    }

    @Test
    fun `stored lines beyond end of file`() {
        val a = anchor().copy(startLine = 40, endLine = 41)
        assertEquals(LineRange(3, 4), AnchorResolver.resolve(a, text))
        assertNull(AnchorResolver.resolve(a, "short"))
    }


    @Test
    fun `a different copy sitting at the stored line is not mistaken for the anchor`() {
        val original = "p\n}\nq\nrest\n"
        val a = AnchorResolver.capture("f.txt", original, 2, 2)
        val changed = "other\n}\nthing\np\n}\nq\nrest\n"
        assertEquals(LineRange(5, 5), AnchorResolver.resolve(a, changed))
    }

    @Test
    fun `copies at the stored line and elsewhere with no telling neighbours are not found`() {
        val a = AnchorResolver.capture("f.txt", "p\n}\nq", 2, 2)
        assertNull(AnchorResolver.resolve(a, "one\n}\ntwo\nthree\n}\nfour"))
    }

    @Test
    fun `unchanged file with repeated blocks keeps its anchor`() {
        val file = "x\n}\ny\nx\n}\ny\n"
        val a = AnchorResolver.capture("f.txt", file, 5, 5)
        assertEquals(LineRange(5, 5), AnchorResolver.resolve(a, file))
    }
}

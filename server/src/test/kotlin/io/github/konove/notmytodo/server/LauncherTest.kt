package io.github.konove.notmytodo.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherTest {
    @Test
    fun `an older Java is told that the server needs Java 21 and how to give it one`() {
        val problem = Launcher.problem("1.8", "/usr/lib/jvm/java-8-openjdk-amd64/jre")!!
        assertTrue(problem, problem.contains("needs Java 21 or newer"))
        assertTrue(problem, problem.contains("Java 1.8 at /usr/lib/jvm/java-8-openjdk-amd64/jre"))
        assertTrue(problem, problem.contains("JAVA_HOME"))
        assertTrue(problem, problem.contains("installServer"))
        assertTrue(Launcher.problem("17", "/jdk17")!!.contains("Java 17"))
    }

    @Test
    fun `Java 21 and newer run the server`() {
        assertNull(Launcher.problem("21", "/jdk21"))
        assertNull(Launcher.problem("25", "/jbr"))
    }

    @Test
    fun `a version it cannot read is let through, for the server to fail on if it must`() {
        assertNull(Launcher.problem("unknown", "/somewhere"))
    }

    @Test
    fun `major versions are read from both the old and the new form`() {
        assertEquals(8, Launcher.major("1.8"))
        assertEquals(11, Launcher.major("11"))
        assertEquals(21, Launcher.major("21.0.2"))
    }
}

package io.github.konove.notmytodo.store

/** Starts another JVM on the test classes, to try what happens between processes. */
object Jvm {
    fun start(mainClass: String, vararg args: String): Process {
        val java = ProcessHandle.current().info().command().orElse("java")
        val classpath = System.getProperty("test.classpath") ?: System.getProperty("java.class.path")
        return ProcessBuilder(listOf(java, "-cp", classpath, mainClass) + args)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
    }
}

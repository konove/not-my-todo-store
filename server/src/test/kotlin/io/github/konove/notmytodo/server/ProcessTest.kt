package io.github.konove.notmytodo.server

import com.google.gson.JsonParser
import io.github.konove.notmytodo.store.TempRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class ProcessTest {
    @Test
    fun `the installed script answers over stdio in a repository`() {
        val tmp = Files.createTempDirectory("nmt-e2e").toRealPath()
        val repo = TempRepo.create(tmp.resolve("e2e"), "git@github.com:me/e2e.git")
        val builder = ProcessBuilder(System.getProperty("server.script")).directory(repo.toFile())
        builder.environment()["NOT_MY_TODO_STORE"] = tmp.resolve("store").toString()
        builder.environment()["JAVA_HOME"] = System.getProperty("java.home")
        val process = builder.redirectError(ProcessBuilder.Redirect.INHERIT).start()
        process.outputStream.bufferedWriter().use {
            it.write("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""" + "\n")
            it.write("""{"jsonrpc":"2.0","method":"notifications/initialized"}""" + "\n")
            it.write("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"todo_create","arguments":{"title":"from the e2e test"}}}""" + "\n")
            it.write("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"todo_list","arguments":{"compact":true}}}""" + "\n")
        }
        val lines = process.inputStream.bufferedReader().readLines()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
        assertEquals(3, lines.size)
        fun text(line: String) = JsonParser.parseString(line).asJsonObject.getAsJsonObject("result")
        assertTrue(text(lines[0]).get("instructions").asString.contains("e2e"))
        assertTrue(text(lines[1]).getAsJsonArray("content")[0].asJsonObject.get("text").asString.contains("created project"))
        assertTrue(text(lines[2]).getAsJsonArray("content")[0].asJsonObject.get("text").asString.contains("from the e2e test"))
        assertTrue(Files.exists(tmp.resolve("store/projects/e2e/items/T-1.json")))
    }
}

package io.github.konove.notmytodo.server

import io.github.konove.notmytodo.anchor.AnchorResolver
import io.github.konove.notmytodo.model.TodoItem
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Files
import java.nio.file.Path

/** Anchors as the code on disk is now. An agent changes files between calls, so they are read every time. */
object AnchorsNow {
    /**
     * [item] with each anchor at the lines its code is at now in [checkout], or lost; as it is without a checkout.
     * The anchors of a fixed or closed item stay where they were when it was, since later commits move its code.
     */
    fun of(item: TodoItem, checkout: Path?): TodoItem {
        if (item.anchorsFrozen) return if (item.anyLost) item.copy(anchors = item.anchors.map { it.copy(lost = false) }) else item
        if (checkout == null || item.anchors.isEmpty()) return item
        val texts = HashMap<String, String?>()
        val anchors = item.anchors.map { a ->
            val text = texts.getOrPut(a.path) { read(checkout, a.path) }
            when {
                text == null -> a.copy(lost = true)
                a.isFile -> a.copy(lost = false)
                else -> AnchorResolver.resolve(a, text)?.let { a.copy(startLine = it.startLine, endLine = it.endLine, lost = false) }
                    ?: a.copy(lost = true)
            }
        }
        return if (anchors == item.anchors) item else item.copy(anchors = anchors)
    }

    /**
     * The text of [path] in [checkout]; null when there is no such file, or it is not inside the
     * checkout. An absolute path is refused even when it leads inside: anchors are relative to the checkout.
     */
    fun read(checkout: Path?, path: String): String? {
        if (checkout == null || path.startsWith('/') || path.startsWith('\\')) return null
        val file = try {
            Path.of(path).takeUnless { it.isAbsolute }?.let { checkout.resolve(it).normalize() }
        } catch (_: InvalidPathException) {
            null
        } ?: return null
        if (!file.startsWith(checkout) || !Files.isRegularFile(file)) return null
        return try {
            // A link inside the checkout may lead out of it.
            if (!file.toRealPath().startsWith(checkout.toRealPath())) return null
            Files.readString(file)
        } catch (_: IOException) {
            null
        }
    }
}

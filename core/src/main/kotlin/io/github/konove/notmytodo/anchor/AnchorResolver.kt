package io.github.konove.notmytodo.anchor

import io.github.konove.notmytodo.model.Anchor

data class LineRange(val startLine: Int, val endLine: Int)

/** Finds where an anchor's text now lives in a file. Pure text logic; lines are 1-based. */
object AnchorResolver {
    private const val NEIGHBOURS = 3

    fun lines(text: String): List<String> = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')

    fun capture(path: String, fileText: String, startLine: Int, endLine: Int): Anchor {
        val all = lines(fileText)
        val start = startLine.coerceIn(1, all.size)
        val end = endLine.coerceIn(start, all.size)
        return Anchor(
            path = path,
            startLine = start,
            endLine = end,
            text = all.subList(start - 1, end).joinToString("\n"),
            before = all.subList(maxOf(0, start - 1 - NEIGHBOURS), start - 1).toList(),
            after = all.subList(end, minOf(all.size, end + NEIGHBOURS)).toList(),
        )
    }

    fun resolve(anchor: Anchor, fileText: String): LineRange? {
        val all = lines(fileText)
        val needle = lines(anchor.text)
        val stored = anchor.startLine - 1
        if (matchesAt(all, needle, stored, String::trimEnd)) {
            // Blank text matches everywhere, so it can only ever be confirmed in place. Other text
            // stays put when its neighbours are intact too; otherwise an identical line (a lone
            // "}") that happens to sit at the old line number would be taken for the anchor.
            val intact = neighbourScore(all, needle.size, stored, anchor, String::trimEnd) ==
                anchor.before.size + anchor.after.size
            if (intact || needle.all { it.isBlank() }) {
                return LineRange(anchor.startLine, anchor.startLine + needle.size - 1)
            }
        }
        if (needle.all { it.isBlank() }) return null
        return search(all, needle, anchor, String::trimEnd) ?: search(all, needle, anchor, String::trim)
    }

    private fun matchesAt(all: List<String>, needle: List<String>, index: Int, norm: (String) -> String): Boolean {
        if (index < 0 || index + needle.size > all.size) return false
        return needle.indices.all { norm(all[index + it]) == norm(needle[it]) }
    }

    private fun search(all: List<String>, needle: List<String>, anchor: Anchor, norm: (String) -> String): LineRange? {
        val hits = (0..all.size - needle.size).filter { matchesAt(all, needle, it, norm) }
        val index = when (hits.size) {
            0 -> return null
            1 -> hits[0]
            else -> {
                val scored = hits.map { it to neighbourScore(all, needle.size, it, anchor, norm) }
                val best = scored.maxOf { it.second }
                scored.filter { it.second == best }.singleOrNull()?.first ?: return null
            }
        }
        return LineRange(index + 1, index + needle.size)
    }

    private fun neighbourScore(all: List<String>, size: Int, index: Int, anchor: Anchor, norm: (String) -> String): Int {
        var score = 0
        anchor.before.forEachIndexed { k, line ->
            val at = index - anchor.before.size + k
            if (at in all.indices && norm(all[at]) == norm(line)) score++
        }
        anchor.after.forEachIndexed { k, line ->
            val at = index + size + k
            if (at in all.indices && norm(all[at]) == norm(line)) score++
        }
        return score
    }
}

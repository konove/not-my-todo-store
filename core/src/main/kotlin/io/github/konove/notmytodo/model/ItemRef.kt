package io.github.konove.notmytodo.model

/**
 * An item id as a link or a tool may give it: `T-12` for an item of the same project, `key/T-12` for
 * one of project `key`. [project] is null for a bare id.
 */
data class ItemRef(val project: String?, val id: String) {
    override fun toString(): String = if (project == null) id else "$project/$id"

    /** This ref as it is stored on an item of project [home]: bare when it names that project. */
    fun storedFrom(home: String): String = if (project == null || project == home) id else "$project/$id"

    companion object {
        /** What a project key may be. */
        val KEY = Regex("[a-z0-9][a-z0-9._-]*")

        /** An id as it may be typed: `T-7`, `t-7`, `7`, `infra/T-7` or `Infra/7`. Null when it is none of those. */
        fun parse(raw: String): ItemRef? {
            val text = raw.trim()
            val slash = text.lastIndexOf('/')
            if (slash < 0) return ItemId.normalize(text)?.let { ItemRef(null, it) }
            val key = text.substring(0, slash).trim().lowercase()
            if (!KEY.matches(key)) return null
            return ItemId.normalize(text.substring(slash + 1))?.let { ItemRef(key, it) }
        }

        /** [raw] as it is stored: `T-12` or `key/T-12`. Null when it is not an id. */
        fun normalize(raw: String): String? = parse(raw)?.toString()

        /** The ids in [text], separated by commas or spaces. Throws [IllegalArgumentException] on anything that is not an id. */
        fun parseList(text: String): List<ItemRef> = text.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.map {
            parse(it) ?: throw IllegalArgumentException("\"$it\" is not an item id: write T-1, T-2, ... or project/T-1")
        }.distinct()
    }
}

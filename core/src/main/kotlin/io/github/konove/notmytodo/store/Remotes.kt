package io.github.konove.notmytodo.store

/**
 * A remote URL as projects compare it: host and path, so that the SSH, HTTPS and scp forms of one
 * repository are the same. A local path is left a path.
 */
object Remotes {
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://(?:[^@/]*@)?([^/:]+)(?::\\d+)?/(.*)$")
    private val SCP = Regex("^(?:[^@/]+@)?([^/:]+):(?!/)(.+)$")
    private val DRIVE = Regex("^[a-zA-Z]:[\\\\/]")

    fun normalize(url: String): String {
        val u = url.trim().trimEnd('/').removeSuffix(".git").trimEnd('/')
        SCHEME.matchEntire(u)?.let { return it.groupValues[1].lowercase() + "/" + it.groupValues[2] }
        if (DRIVE.containsMatchIn(u)) return u
        SCP.matchEntire(u)?.let { return it.groupValues[1].lowercase() + "/" + it.groupValues[2].trimStart('/') }
        return u
    }
}

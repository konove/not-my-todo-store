package io.github.konove.notmytodo.store

import java.nio.file.Path

/** Where the store of this machine is kept. */
object StoreRoot {
    const val ENV = "NOT_MY_TODO_STORE"
    private const val NAME = "not-my-todo-store"

    fun default(
        env: Map<String, String> = System.getenv(),
        os: String = System.getProperty("os.name"),
        home: Path = Path.of(System.getProperty("user.home")),
    ): Path {
        env[ENV]?.takeIf { it.isNotBlank() }?.let { return Path.of(it).toAbsolutePath().normalize() }
        fun dir(name: String): Path? = env[name]?.takeIf { it.isNotBlank() }?.let(Path::of)
        val system = os.lowercase()
        return when {
            system.startsWith("windows") -> (dir("LOCALAPPDATA") ?: home.resolve("AppData").resolve("Local")).resolve(NAME)
            system.startsWith("mac") -> home.resolve("Library").resolve("Application Support").resolve(NAME)
            else -> (dir("XDG_DATA_HOME") ?: home.resolve(".local").resolve("share")).resolve(NAME)
        }
    }
}

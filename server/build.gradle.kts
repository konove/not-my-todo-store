import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    testImplementation(testFixtures(project(":core")))
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("io.github.konove.notmytodo.server.MainKt")
    applicationName = "notmytodo-store"
}

tasks.test {
    dependsOn(tasks.installDist)
    systemProperty("server.script", layout.buildDirectory.file("install/notmytodo-store/bin/notmytodo-store").get().asFile.path)
}

/** The store's root, by the rules of StoreRoot in core: the server is installed beside the projects. */
val storeRoot: File = System.getenv("NOT_MY_TODO_STORE")?.takeIf { it.isNotBlank() }?.let(::File) ?: run {
    val home = File(System.getProperty("user.home"))
    val os = System.getProperty("os.name").lowercase()
    when {
        os.startsWith("windows") -> File(System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: File(home, "AppData/Local").path, "not-my-todo-store")
        os.startsWith("mac") -> File(home, "Library/Application Support/not-my-todo-store")
        else -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: File(home, ".local/share").path, "not-my-todo-store")
    }
}
val appDir = File(storeRoot, "app")
val windows = System.getProperty("os.name").lowercase().startsWith("windows")
val startScript = File(appDir, "bin/notmytodo-store" + if (windows) ".bat" else "")

val copyServer by tasks.registering(Sync::class) {
    description = "Copies the server to the store's app folder."
    from(tasks.installDist.map { it.destinationDir })
    into(appDir)
}

/** Registers the installed server with Claude Code for every project of this user. */
abstract class RegisterServer : DefaultTask() {
    @get:Inject
    abstract val exec: ExecOperations

    @get:Input
    abstract val script: Property<String>

    @TaskAction
    fun register() {
        val command = "claude mcp add --scope user notmytodo-store -- ${script.get()}"
        val claude = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .flatMap { listOf(File(it, "claude"), File(it, "claude.cmd"), File(it, "claude.exe")) }
            .firstOrNull { it.canExecute() }
        if (claude == null) {
            logger.lifecycle("Claude Code is not on PATH. Register the server with:\n  $command")
            return
        }
        // An older registration may point elsewhere; adding over it would fail.
        exec.exec {
            commandLine(claude.path, "mcp", "remove", "--scope", "user", "notmytodo-store")
            isIgnoreExitValue = true
            standardOutput = ByteArrayOutputStream()
            errorOutput = ByteArrayOutputStream()
        }
        exec.exec { commandLine(claude.path, "mcp", "add", "--scope", "user", "notmytodo-store", "--", script.get()) }
        logger.lifecycle("Registered notmytodo-store: ${script.get()}")
    }
}

tasks.register<RegisterServer>("installServer") {
    group = "distribution"
    description = "Installs the server to the store's app folder and registers it with Claude Code."
    dependsOn(copyServer)
    script.set(startScript.path)
}

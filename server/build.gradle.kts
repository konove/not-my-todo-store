import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    kotlin("jvm")
    application
}

/** The Java the server is compiled for and needs to run. */
val serverJava = 21

kotlin {
    jvmToolchain(serverJava)
}

repositories {
    mavenCentral()
}

// The entry point, compiled for Java 8 so that an older Java says it is too old instead of failing to load the server.
val launcher: SourceSet by sourceSets.creating

tasks.named<JavaCompile>(launcher.compileJavaTaskName) {
    options.release.set(8)
    options.compilerArgs.add("-Xlint:-options")
}

tasks.jar {
    from(launcher.output)
}

dependencies {
    implementation(project(":core"))
    testImplementation(testFixtures(project(":core")))
    testImplementation(launcher.output)
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("io.github.konove.notmytodo.server.Launcher")
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

/**
 * The Java the server is registered to run with, whatever Java a session's shell has: the one running
 * this build when it is new enough, else the toolchain's, which compiled the server.
 */
val registeredJava: Provider<String> =
    if (JavaVersion.current().isCompatibleWith(JavaVersion.toVersion(serverJava))) {
        provider { System.getProperty("java.home") }
    } else {
        javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(serverJava)) }
            .map { it.metadata.installationPath.asFile.path }
    }

val copyServer by tasks.registering(Sync::class) {
    description = "Copies the server to the store's app folder."
    from(tasks.installDist.map { it.destinationDir })
    into(appDir)
}

/** Registers the installed server with Claude Code for every project of this user, to run with [javaHome]. */
abstract class RegisterServer : DefaultTask() {
    @get:Inject
    abstract val exec: ExecOperations

    @get:Input
    abstract val script: Property<String>

    @get:Input
    abstract val javaHome: Property<String>

    @TaskAction
    fun register() {
        // The name comes before -e, which would take it as a second variable.
        val add = listOf("mcp", "add", "notmytodo-store", "--scope", "user", "-e", "JAVA_HOME=${javaHome.get()}", "--", script.get())
        val command = "claude " + add.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it }
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
        exec.exec { commandLine(listOf(claude.path) + add) }
        logger.lifecycle("Registered notmytodo-store: ${script.get()}, with Java ${javaHome.get()}")
    }
}

tasks.register<RegisterServer>("installServer") {
    group = "distribution"
    description = "Installs the server to the store's app folder and registers it with Claude Code."
    dependsOn(copyServer)
    script.set(startScript.path)
    javaHome.set(registeredJava)
}

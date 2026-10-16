plugins {
    // Downloads the JDK that jvmToolchain asks for when the machine does not have it.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "not-my-todo-store"
include("core", "server")

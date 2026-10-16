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

plugins {
    kotlin("jvm")
    `java-library`
    `java-test-fixtures`
    `maven-publish`
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    api("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // Tests start other JVMs on the same classes to try the lock between processes.
    systemProperty("test.classpath", sourceSets.test.get().runtimeClasspath.asPath)
}

publishing {
    publications {
        create<MavenPublication>("core") {
            artifactId = "not-my-todo-store-core"
            from(components["java"])
        }
    }
}

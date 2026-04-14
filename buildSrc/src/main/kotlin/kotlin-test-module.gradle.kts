plugins {
    id("java-test-module")
}

dependencies {
    testImplementation(kotlin("test-junit5"))
    testImplementation("io.kotest:kotest-runner-junit5:${Versions.kotest}")
    testImplementation("io.kotest:kotest-assertions-core:${Versions.kotest}")
}

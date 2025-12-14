plugins {
    kotlin("jvm") version "2.2.21"
}

dependencies {
    implementation("io.projectreactor:reactor-core:3.6.11")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

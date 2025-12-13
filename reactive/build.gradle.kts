plugins {
    kotlin("jvm") version "2.2.21"
}

dependencies {
    implementation("io.projectreactor:reactor-core:3.6.11")
}

kotlin {
    jvmToolchain(21)
}

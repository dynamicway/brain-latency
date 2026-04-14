plugins {
    kotlin("jvm") version "2.2.21"
}

group = "bee.brainlatency"
version = "0.0.1"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

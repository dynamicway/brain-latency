plugins {
    id("java-module")
    kotlin("jvm")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    implementation(kotlin("reflect"))
}

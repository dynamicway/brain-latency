plugins {
    id("kotlin-module")
    id("kotlin-test-module")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.coroutines}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${Versions.coroutines}")
}

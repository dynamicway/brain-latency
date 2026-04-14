plugins {
    id("kotlin-module")
    id("kotlin-test-module")
}

dependencies {
    implementation("io.projectreactor:reactor-core:${Versions.reactor}")
}

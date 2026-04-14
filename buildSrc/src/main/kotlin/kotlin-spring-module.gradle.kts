plugins {
    id("spring-base-module")
    id("kotlin-module")
    kotlin("plugin.spring")
}

dependencies {
    implementation("tools.jackson.module:jackson-module-kotlin")
}

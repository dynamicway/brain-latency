plugins {
    id("kotlin-module")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.jetbrains.kotlin.plugin.spring")
}

dependencies {
    "implementation"("org.springframework.boot:spring-boot-starter-webflux")
    "implementation"("tools.jackson.module:jackson-module-kotlin")
}

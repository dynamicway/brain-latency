plugins {
    id("java-module")
    id("kotlin-module")
    id("io.spring.dependency-management")
    kotlin("plugin.spring")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${Versions.springBoot}")
    }
}

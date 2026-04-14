plugins {
    id("io.spring.dependency-management")
    java
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${Versions.springCloud}")
    }
}

dependencies {
    implementation("org.springframework.cloud:spring-cloud-starter-openfeign")
}

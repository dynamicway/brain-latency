plugins {
    id("kotlin-spring-module")
    id("kotlin-test-module")
    id("kotlin-jpa-module") // Kotlin 특화 JPA 모듈 사용
    id("spring-feign-module")
}

group = "bee.brainlatency"
version = "0.0.1-SNAPSHOT"
description = "spring-web"

dependencies {
    implementation(project(":nio-sample:mydata-domain"))
    implementation("org.springframework.boot:spring-boot-h2console")
    runtimeOnly("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

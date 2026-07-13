plugins {
    id("spring-lib-module")
    id("kotlin-test-module")
}

group = "bee.brainlatency"
version = "0.0.1"
description = "redis-lease-cache"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    testImplementation("io.kotest.extensions:kotest-extensions-spring:1.3.0")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

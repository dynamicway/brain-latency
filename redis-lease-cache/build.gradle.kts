plugins {
    id("spring-lib-module")
    id("kotlin-test-module")
}

group = "bee.brainlatency"
version = "0.0.1"
description = "redis-lease-cache"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

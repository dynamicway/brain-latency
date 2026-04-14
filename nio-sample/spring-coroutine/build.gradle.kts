plugins {
    id("kotlin-webflux-module")
    id("kotlin-test-module")
}

group = "bee.brainlatency"
version = "0.0.1-SNAPSHOT"
description = "spring-coroutine"

dependencies {
    implementation(project(":nio-sample:mydata-domain"))
    testImplementation("io.kotest.extensions:kotest-extensions-spring:1.3.0")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

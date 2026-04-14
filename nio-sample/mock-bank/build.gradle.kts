plugins {
    id("kotlin-spring-module")
    id("kotlin-test-module")
}

group = "bee.brainlatency"
version = "0.0.1-SNAPSHOT"
description = "mock-bank"

dependencies {
    implementation(project(":nio-sample:mydata-domain"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

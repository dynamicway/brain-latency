plugins {
    id("spring-lib-module")
    id("kotlin-test-module")
}

group = "bee.brainlatency"
version = "0.0.1"
description = "redis-lease-cache"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // The JSON value-serializer example wires Spring's GenericJacksonJsonRedisSerializer,
    // whose builder generics expose Jackson's JsonMapper on the compile classpath.
    testImplementation("tools.jackson.core:jackson-databind")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

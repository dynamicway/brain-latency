plugins {
    java
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:${Versions.junit}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":kotlin"))
    implementation("org.springframework.boot:spring-boot-gradle-plugin:${Versions.springBoot}")
    implementation("io.spring.gradle:dependency-management-plugin:${Versions.springDependencyManagement}")
    implementation("org.jetbrains.kotlin:kotlin-allopen:${Versions.kotlin}")
    implementation("org.jetbrains.kotlin:kotlin-noarg:${Versions.kotlin}")
}

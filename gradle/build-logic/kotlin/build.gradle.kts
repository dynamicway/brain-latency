plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":jvm"))
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${Versions.kotlin}")
}

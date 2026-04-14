plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":jvm"))
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
}

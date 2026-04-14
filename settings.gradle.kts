pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

includeBuild("gradle/build-logic")

rootProject.name = "brain-latency"

include("io-model")
include("reactive")
include("kotlin")
include("nio-sample")
include("nio-sample:mydata-domain")
include("nio-sample:spring-web")
include("nio-sample:mock-bank")
include("nio-sample:mydata-domain")
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ZenRead"

include(":app")
include(":core:common")
include(":core:model")
include(":core:database")
include(":core:datastore")
include(":core:network")
include(":core:designsystem")
include(":reader:engine")
include(":data:library")
include(":data:sync")
include(":feature:library")
include(":feature:reader")
include(":feature:notes")
include(":feature:settings")
include(":data:translate")
include(":data:tts")
include(":data:ai")
include(":data:fonts")
include(":feature:fonts")

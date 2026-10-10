pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "autobot"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
include(":core:designsystem")
include(":core:security")
include(":core:network")
include(":core:data")
include(":core:diffusion")
include(":core:models")
include(":providers:remote")
include(":agent:core")
include(":agent:runtime")
include(":engine:llama")
include(":engine:diffusion")
include(":engine:npu")
include(":engine:speech")
include(":feature:chat")
include(":feature:settings")
include(":feature:imagine")
include(":feature:models")
include(":feature:home")

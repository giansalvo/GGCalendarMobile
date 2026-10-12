pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com.android.*")
                includeGroupByRegex("com.google.*")
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

rootProject.name = "GGCalendar"

include(":app")
include(":core:model")
include(":core:network")
include(":core:auth")
include(":core:database")
include(":core:data")
include(":core:notifications")
include(":feature:calendar")
include(":feature:event")
include(":feature:settings")

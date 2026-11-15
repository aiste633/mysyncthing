// Google's repository is asked only for Google's own groups, so it can never answer for anyone
// else's library; gradle/verification-metadata.xml pins every artifact besides. Written out
// twice: pluginManagement is evaluated on its own, before the rest of this file.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google(\\..*)?")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google(\\..*)?")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "mysyncthing"
include(":app", ":core")

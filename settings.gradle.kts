pluginManagement {
    repositories {
        maven {
            url = uri("file:///home/hatch/workspace/android-dev/maven-cache/")
        }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri("file:///home/hatch/workspace/android-dev/maven-cache/")
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "Fingerprint"
include(":app")

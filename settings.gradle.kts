pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // Same artifacts on alternate hosts, used when dl.google.com / repo.maven.apache.org are unreachable from CI
        maven { url = uri("https://maven.google.com") }
        maven { url = uri("https://repo1.maven.org/maven2") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.google.com") }
        maven { url = uri("https://repo1.maven.org/maven2") }
    }
}

rootProject.name = "weatherwall"
include(":app")

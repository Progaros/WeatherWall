pluginManagement {
    repositories {
        // Google answers 404 to the CI runner's VPN exit IP, so the mirror is queried first;
        // google() stays as fallback for developer machines and once the block is lifted.
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        google()
        mavenCentral()
    }
}

rootProject.name = "weatherwall"
include(":app")

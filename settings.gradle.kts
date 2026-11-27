pluginManagement {
    repositories {
        google()
        // Google answers 404 to the CI runner's VPN exit IP, so this mirror serves the Android
        // artifacts. It only mirrors Google's Maven and answers 502 for anything else, which
        // would disable the repository for the whole build instead of falling through like a
        // 404 - hence the content filter.
        maven {
            url = uri("https://maven.aliyun.com/repository/google")
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroup("com.google.testing.platform")
            }
        }
        // Central's CDN rate-limits (429) the CI runner's shared VPN exit IP; Google's GCS
        // mirror has no rate limit, and mavenCentral() stays as fallback.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven {
            url = uri("https://maven.aliyun.com/repository/google")
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroup("com.google.testing.platform")
            }
        }
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
        mavenCentral()
    }
}

rootProject.name = "weatherwall"
include(":app")

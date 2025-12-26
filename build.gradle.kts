plugins {
    id("com.android.application") version "8.11.1" apply false
    kotlin("android") version "1.9.22" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "info.olli.weatherlwp"
    compileSdk = 35

    defaultConfig {
        applicationId = "info.olli.weatherlwp"
        minSdk = 26
        targetSdk = 35
        versionCode = 226
        versionName = "1.30.20"
    }

    buildFeatures {
        buildConfig = true
    }

    // Signing config for consistent APK signatures between CI and local builds
    signingConfigs {
        create("ciDebug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("ciDebug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    implementation("androidx.preference:preference-ktx:1.2.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// PIT Mutation Testing - runs against debug unit test classes
// Usage: ./gradlew pitest
configurations.create("pitest")
dependencies {
    "pitest"("org.pitest:pitest-command-line:1.17.4")
    "pitest"("org.pitest:pitest-entry:1.17.4")
}

tasks.register<JavaExec>("pitest") {
    dependsOn("testDebugUnitTest")
    group = "verification"
    description = "Run PIT mutation testing"
    
    mainClass.set("org.pitest.mutationtest.commandline.MutationCoverageReport")
    classpath = configurations["pitest"]
    
    doFirst {
        val buildDir = layout.buildDirectory.get().asFile
        val runtimeCp = configurations["debugUnitTestRuntimeClasspath"].resolve().joinToString(",")
        
        val appClassesDirs = listOf(
            File(buildDir, "tmp/kotlin-classes/debug"),
            File(buildDir, "intermediates/javac/debug/classes")
        ).filter { it.exists() }
        
        val testClassesDirs = listOf(
            File(buildDir, "tmp/kotlin-classes/debugUnitTest")
        ).filter { it.exists() }
        
        val allClasspath = (appClassesDirs + testClassesDirs).joinToString(",") { it.absolutePath } +
            "," + runtimeCp +
            ",/opt/android-sdk-linux/platforms/android-35/android.jar"
        
        // Log classpath for debugging
        appClassesDirs.forEach { dir ->
            logger.lifecycle("PIT app classes: ${dir.absolutePath} (exists=${dir.exists()}, files=${dir.walkTopDown().filter { it.extension == "class" }.count()})")
        }
        testClassesDirs.forEach { dir ->
            logger.lifecycle("PIT test classes: ${dir.absolutePath} (exists=${dir.exists()}, files=${dir.walkTopDown().filter { it.extension == "class" }.count()})")
        }
        
        setArgs(listOf(
            "--reportDir", File(buildDir, "reports/pitest").absolutePath,
            "--targetClasses", "info.olli.weatherlwp.rendering.*,info.olli.weatherlwp.simulation.*,info.olli.weatherlwp.util.*,info.olli.weatherlwp.parallax.*,info.olli.weatherlwp.weather.*",
            "--excludedClasses", "*Test,*Test$*",
            "--targetTests", "info.olli.weatherlwp.*",
            "--sourceDirs", file("src/main/java").absolutePath,
            "--classPath", allClasspath,
            "--outputFormats", "HTML,XML",
            "--threads", "4",
            "--mutators", "DEFAULTS",
            "--skipFailingTests"
        ))
    }
}

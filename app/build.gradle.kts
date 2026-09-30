import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "info.olli.weatherlwp"
    compileSdk = 35

    defaultConfig {
        applicationId = "info.olli.weatherlwp"
        minSdk = 26
        targetSdk = 35
        versionCode = 284
        versionName = "1.35.4"
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
            // Same key as published debug builds so existing installs keep updating.
            signingConfig = signingConfigs.getByName("ciDebug")
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    // The suite is dominated by Robolectric classes that each boot their own Android
    // runtime, so wall time scales with fork count rather than with a single JVM.
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 6)
    maxHeapSize = "1536m"

    // Robolectric downloads its android-all artifacts from the forked test JVM,
    // which does not inherit the Gradle JVM's proxy configuration.
    listOf(
        "http.proxyHost", "http.proxyPort",
        "https.proxyHost", "https.proxyPort",
        "http.nonProxyHosts",
        "readme.screenshots",
        "render.artifacts"
    ).forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }

    // Fast local loop: `-PskipRenderTests` drops the heavy Robolectric render suites. CI runs everything.
    val skipRenderTests = providers.gradleProperty("skipRenderTests").map { it != "false" }.getOrElse(false)
    if (skipRenderTests) {
        filter {
            excludeTestsMatching("info.olli.weatherlwp.EventRenderTest")
            excludeTestsMatching("info.olli.weatherlwp.SceneScreenshotTest")
            excludeTestsMatching("info.olli.weatherlwp.ReadmeScreenshotTest")
            excludeTestsMatching("info.olli.weatherlwp.settings.EventPreviewViewTest")
        }
    }
}

// CI reads the release version from here instead of grepping this file.
tasks.register("printVersionName") {
    val versionName = checkNotNull(android.defaultConfig.versionName) { "versionName is not set" }
    doLast { println(versionName) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.robolectric)
}

// PIT Mutation Testing - runs against debug unit test classes
// Usage: ./gradlew pitest
configurations.create("pitest")
dependencies {
    "pitest"(libs.pitest.command.line)
    "pitest"(libs.pitest.entry)
}

tasks.register<JavaExec>("pitest") {
    dependsOn("testDebugUnitTest")
    group = "verification"
    description = "Run PIT mutation testing"
    
    mainClass.set("org.pitest.mutationtest.commandline.MutationCoverageReport")
    classpath = configurations["pitest"]
    
    val androidJar = android.sdkDirectory.resolve("platforms/android-${android.compileSdk}/android.jar")

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
            "," + androidJar.absolutePath
        
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

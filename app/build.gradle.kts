import org.gradle.api.tasks.testing.Test

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// applicationId defaults to com.example.shutdownprotection; override with:
//   .\gradlew.bat :app:assembleDebug -Pspm.appId=com.example.spstest
// The Kotlin namespace below stays stable; only applicationId changes. Every
// action string and component name the app uses is derived from the actual
// applicationId at runtime (see scheduling/AlarmActions.kt) rather than hardcoded.
val spmAppId: String = providers.gradleProperty("spm.appId").getOrElse("com.example.shutdownprotection")
// API 36 Robolectric runs on JDK 21; the Android app itself remains compiled for JVM 17.
// Pass -Pspm.testJavaHome=<JDK 21 home> or set JAVA21_HOME when Gradle itself uses JDK 17.
val spmTestJavaHome: String? = providers.gradleProperty("spm.testJavaHome").orNull
    ?: System.getenv("JAVA21_HOME")

android {
    namespace = "com.example.shutdownprotection"
    // Compile/target stay 36. The separate compatibility flavor supports API 29+.
    compileSdk = 36

    defaultConfig {
        applicationId = spmAppId
        minSdk = 36
        targetSdk = 36
        versionCode = 6
        versionName = "0.4.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "edition"
    productFlavors {
        create("simple") {
            dimension = "edition"
            buildConfigField("boolean", "MANAGED_TOOLS", "false")
            buildConfigField("boolean", "COMPATIBILITY_EDITION", "false")
            manifestPlaceholders["powerPauseLabel"] = "Power Pause Simple"
        }
        create("compatibility") {
            dimension = "edition"
            applicationIdSuffix = ".compatibility"
            minSdk = 29
            versionCode = 7
            versionName = "0.4.2"
            buildConfigField("boolean", "MANAGED_TOOLS", "false")
            buildConfigField("boolean", "COMPATIBILITY_EDITION", "true")
            manifestPlaceholders["powerPauseLabel"] = "Power Pause Compatibility"
        }
        create("advanced") {
            dimension = "edition"
            buildConfigField("boolean", "MANAGED_TOOLS", "true")
            buildConfigField("boolean", "COMPATIBILITY_EDITION", "false")
            applicationIdSuffix = ".advanced"
            manifestPlaceholders["powerPauseLabel"] = "Power Pause Advanced"
        }
    }

    buildTypes {
        debug {
            // No applicationIdSuffix: the default ID must stay provisionable
            // as Device Owner without suffix surprises.
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            // Local releases retain the existing certificate for in-place upgrades.
            // The private keystore is never committed. CI publishes test APKs only.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Optional override of the default debug signing config. AGP normally creates
    // and uses ~/.android/debug.keystore, which is not writable in every
    // environment (sandboxes, CI images with a read-only home). Setting
    // DEBUG_KEYSTORE_PATH points it somewhere writable (same mechanism as the
    // proven Linksi precedent). When the variable is absent this block is a
    // no-op and AGP behaves exactly as before.
    signingConfigs {
        getByName("debug") {
            System.getenv("DEBUG_KEYSTORE_PATH")?.let { path ->
                storeFile = file(path)
                storePassword = System.getenv("DEBUG_KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("DEBUG_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("DEBUG_KEY_PASSWORD") ?: "android"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        // AlarmActions and diagnostics need the real applicationId at runtime, so the
        // appId override property has to reach the compiled code.
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// AGP's UnitTestOptions.all action does not expose the Gradle Test task's fork options.
// Configure the actual Test tasks so app compilation stays on Java 17 while Robolectric runs
// on the JDK 21 executable required by Android API 36.
tasks.withType<Test>().configureEach {
    // Use the official Maven Central endpoint for Robolectric's Android runtimes.
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    if (name.startsWith("testSimple") || name.startsWith("testCompatibility")) {
        // Managed regression tests belong to Advanced. Simple executes its actual
        // service/gate tests plus manifest/startup edition-isolation checks.
        filter {
            includeTestsMatching("com.example.shutdownprotection.noreset.*")
            includeTestsMatching("com.example.shutdownprotection.EditionIsolationTest")
        }
    }
    if (spmTestJavaHome != null) {
        val javaHome = file(spmTestJavaHome)
        val executableName = if (System.getProperty("os.name").lowercase().contains("win")) {
            "java.exe"
        } else {
            "java"
        }
        val javaExecutable = javaHome.resolve("bin").resolve(executableName)
        executable = javaExecutable.absolutePath
        doFirst {
            if (!javaHome.isDirectory || !javaExecutable.isFile) {
                throw GradleException(
                    "spm.testJavaHome must point to a JDK with $javaExecutable",
                )
            }
            val releaseFile = javaHome.resolve("release")
            val versionText = releaseFile.takeIf { it.isFile }
                ?.readLines()
                ?.firstOrNull { it.startsWith("JAVA_VERSION=") }
                ?.substringAfter('=')
                ?.trim('"')
            if (versionText == null || JavaVersion.toVersion(versionText) < JavaVersion.VERSION_21) {
                throw GradleException(
                    "API 36 Robolectric tests require JDK 21 at $javaHome. " +
                        "The app still compiles to JVM 17.",
                )
            }
        }
    } else {
        doFirst {
            if (JavaVersion.current() < JavaVersion.VERSION_21) {
                throw GradleException(
                    "API 36 Robolectric tests require JDK 21. Set " +
                        "-Pspm.testJavaHome=<JDK 21 home> or JAVA21_HOME. " +
                        "The app still compiles to JVM 17.",
                )
            }
        }
    }
}

dependencies {
    // Core - versions from the proven Linksi precedent (E:\Deepseek\Linksi\repo\app\build.gradle).
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose BOM + Material 3 (Linksi precedent: BOM 2024.10.00).
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Lifecycle + ViewModel for Compose.
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // DataStore Preferences: one instance per file (brief section 7.7).
    "advancedImplementation"("androidx.datastore:datastore-preferences:1.1.7")

    // Kotlin coroutines.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Unit tests: pure seams plus API 36 receiver/framework checks under Robolectric.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.robolectric:robolectric:4.16.1")

    // Instrumented tests assert public API state only (brief section 26). They are
    // not a substitute for the manual physical power-menu observations.
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

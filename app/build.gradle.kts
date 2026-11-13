plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "de.meshcore.pocketmonitor"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "de.meshcore.pocketmonitor"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "de.meshcore.pocketmonitor.HistoryInstrumentation"
    }
    buildFeatures { compose = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.profileinstaller:profileinstaller:1.4.0")
    testImplementation("junit:junit:4.13.2")
}

// Locally signed release for side-by-side profiling on a development device.
if (providers.gradleProperty("profileRelease").isPresent) {
    android.buildTypes.getByName("release") {
        applicationIdSuffix = ".profiling"
        signingConfig = android.signingConfigs.getByName("debug")
    }
    android.sourceSets.getByName("release") {
        manifest.srcFile("src/profileRelease/AndroidManifest.xml")
    }
    if (providers.gradleProperty("scrollBenchmark").isPresent) {
        android.sourceSets.getByName("release") {
            java.srcDir("src/scrollBenchmark/java")
            manifest.srcFile("src/profileScrollBenchmark/AndroidManifest.xml")
        }
    }
}

// Opt-in synthetic scroll fixture, installed alongside the regular app.
if (providers.gradleProperty("scrollBenchmark").isPresent) {
    android.buildTypes.getByName("debug").applicationIdSuffix = ".scrollbenchmark"
    android.sourceSets.getByName("debug") {
        java.srcDir("src/scrollBenchmark/java")
        manifest.srcFile("src/scrollBenchmark/AndroidManifest.xml")
    }
}

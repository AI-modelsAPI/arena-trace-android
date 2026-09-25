import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.ati.arena"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ati.arena"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "0.5.3"
    }

    // Fixed release signing (keystore lives in GitHub Secrets; CI decodes it and
    // points ARENA_KEYSTORE at the file). Local builds without these env vars
    // simply produce an unsigned release APK — debug builds are unaffected.
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("ARENA_KEYSTORE")
            if (!ksPath.isNullOrBlank()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("ARENA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ARENA_KEY_ALIAS") ?: "arena"
                keyPassword = System.getenv("ARENA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!System.getenv("ARENA_KEYSTORE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Origin-restricted page channel + document-start script injection (with a
    // runtime fallback when the device's WebView lacks either feature).
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Real org.json for JVM unit tests: Android's bundled org.json is a stub
    // that throws "not mocked" when run on the local JVM.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

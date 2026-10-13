plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.mezo.trace"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mezo.trace"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Opt-in debug-signed release builds for on-device manual testing:
        //   ./gradlew :app:assembleRelease -PtraceReleaseSigning=debug
        // Uses the standard developer debug keystore; production signing
        // stays outside the repository (AGENTS.md — no committed secrets).
        if (project.findProperty("traceReleaseSigning") == "debug") {
            create("debugSign") {
                storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (project.findProperty("traceReleaseSigning") == "debug") {
                signingConfig = signingConfigs.getByName("debugSign")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    defaultConfig {
        // Default suits the emulator (host loopback = 10.0.2.2). For physical
        // devices pass -PtraceBaseUrl=localhost together with
        // `adb reverse tcp:8080 tcp:8080`. For a cloud deployment pass the
        // full URL, e.g. -PtraceBaseUrl=https://trace-api.onrender.com —
        // anything containing "://" is used verbatim.
        val baseHost = (project.findProperty("traceBaseUrl") as String?) ?: "10.0.2.2"
        val baseUrl = if (baseHost.contains("://")) baseHost else "http://$baseHost:8080"
        buildConfigField("String", "BASE_URL", "\"$baseUrl\"")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
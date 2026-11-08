import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.aipro.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aipro.camera"
        minSdk = 31
        targetSdk = 35
        versionCode = 21
        versionName = "1.4.0"
        // Stamped at configure time so the on-screen label proves which build is actually running.
        val buildStamp = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())
        buildConfigField("String", "BUILD_TIME", "\"$buildStamp\"")
        // Target device is arm64 only (Galaxy Z Flip 6), so don't ship ~10 MB of other ABIs.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        // The release APK is signed with the local debug keystore so it can be installed on the test
        // device without shipping a private key in the repo. Replace before any store distribution.
        create("localTest") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("localTest")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug { isDebuggable = true }
    }

    lint {
        // AGP's lifecycle detector crashes on this Kotlin analysis API (IncompatibleClassChangeError).
        disable += "NullSafeMutableLiveData"
        checkReleaseBuilds = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:camera"))
    implementation(project(":core:storage"))
    implementation(project(":core:ai"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.window)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // LiteRT / LiteRT-API jars carry newer Kotlin metadata than the project compiler.
        freeCompilerArgs.addAll("-Xskip-metadata-version-check")
    }
}

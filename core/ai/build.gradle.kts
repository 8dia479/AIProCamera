plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.aipro.ai"
    compileSdk = 35
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        disable += "NullSafeMutableLiveData"
        checkReleaseBuilds = false
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    // LiteRT ships native libraries; keep the APK to the ABI the Z Flip 6 actually uses.
    defaultConfig { ndk { abiFilters += listOf("arm64-v8a") } }
}

dependencies {
    api(project(":core:common"))
    api(project(":core:native"))
    // LiteRT (the runtime formerly known as TensorFlow Lite) - fully on-device, no network.
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    // Qualcomm AI Engine Direct QNN HTP delegate for Snapdragon NPU acceleration.
    implementation("com.qualcomm.qti:qnn-runtime:2.34.0")
    implementation("com.qualcomm.qti:qnn-litert-delegate:2.34.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // LiteRT / LiteRT-API jars carry newer Kotlin metadata than the project compiler.
        freeCompilerArgs.addAll("-Xskip-metadata-version-check")
    }
}

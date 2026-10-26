plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "eu.stedb.mysyncthing"
    compileSdk = 37
    // Also strips the Go .so files, so it must match the NDK go/build.sh uses.
    ndkVersion = providers.gradleProperty("mysyncthing.ndkVersion").get()

    defaultConfig {
        applicationId = "eu.stedb.mysyncthing"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Android 15+ phones are arm64; debug builds keep x86_64 for the emulator.
            ndk { abiFilters += "arm64-v8a" }
            // Reproducible builds: no git commit in the APK, so a source tarball build matches too.
            vcsInfo { include = false }
        }
    }

    // F-Droid: no Google dependency-info blob in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { compose = true }
    packaging { jniLibs { useLegacyPackaging = false } }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.zxing.embedded)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "eu.stedb.mysyncthing.core"
    compileSdk = 36
    ndkVersion = providers.gradleProperty("mysyncthing.ndkVersion").get()
    defaultConfig {
        minSdk = 35
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin { jvmToolchain(21) }

val goDir = rootProject.layout.projectDirectory.dir("go")
val bridgeAar = goDir.file("build/stbridge.aar")
val ndkDir = androidComponents.sdkComponents.ndkDirectory

val buildGoBridge by tasks.registering(Exec::class) {
    description = "Builds the Syncthing core as an AAR with gomobile"
    workingDir = goDir.asFile
    commandLine("./build.sh")
    inputs.dir(goDir.dir("bridge"))
    inputs.file(goDir.file("syncthing/go.mod")).optional()
    inputs.file(goDir.file("syncthing.version"))
    outputs.file(bridgeAar)
    // The pinned NDK, not whatever ANDROID_NDK_HOME says; build.sh checks its version.
    doFirst { environment("ANDROID_NDK_HOME", ndkDir.get().asFile.path) }
}

dependencies {
    // Local AAR: fine for app builds; publishing :core as an AAR is not a goal.
    // builtBy lets consumers (e.g. :app desugaring) wait for the AAR task.
    api(files(bridgeAar).builtBy(buildGoBridge))
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
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
        // End-to-end tests on an emulator against a real Syncthing peer (scripts/e2e/run.py).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        // en-XA (long accented text) and ar-XB (right to left) to check layouts; not in releases.
        debug { isPseudoLocalesEnabled = true }
    }

    // locales_config for Android's per-app language page, from the languages in res/ (base: resources.properties).
    // Only shipped languages reach the APK and that page; the filter also drops libraries' other translations.
    androidResources {
        generateLocaleConfig = true
        localeFilters += shippedLanguages()
    }
    // A translation may lack a few strings: those show in English (see shippedLanguages).
    lint { disable += "MissingTranslation" }

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
    // Screen tests run on Robolectric, which needs the merged resources.
    testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin { jvmToolchain(21) }

// XmlStrings: unit tests read the English strings straight from res/values.
androidComponents {
    onVariants { v -> v.hostTests.values.forEach { it.sources.kotlin?.addStaticSourceDirectory("../core/src/testShared/java") } }
}

val robolectricSdk: Configuration by configurations.creating

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
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    // createComposeRule's empty activity; debug builds only, never in a release.
    debugImplementation(libs.compose.ui.test.manifest)
    robolectricSdk(libs.robolectric.android.all)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

// Robolectric's Android jar is a Gradle dependency, so gradle/verification-metadata.xml checks
// it like any other; Robolectric runs offline and never downloads one itself.
val robolectricJars = layout.buildDirectory.dir("robolectric")
val robolectricSync = tasks.register<Sync>("robolectricSdk") {
    from(robolectricSdk)
    into(robolectricJars)
}
tasks.withType<Test>().configureEach {
    dependsOn(robolectricSync)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricJars.get().asFile.path)
    // Real rendering (Skia), for the screenshots.
    systemProperty("robolectric.graphicsMode", "NATIVE")
    // Android 16's shared memory setup reaches into FileDescriptor internals.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
}

// Reference screenshots live in the repository; unit test runs compare against them
// (CI runs verifyRoborazziDebug). After a deliberate change: ./gradlew recordRoborazziDebug
roborazzi { outputDir.set(file("src/test/screenshots")) }

/**
 * English, and each translation that covers at least 90 % of the translatable
 * strings and plurals of both modules (the owner's rule): a missing string shows
 * in English. A language below that stays out of the APK and the language list,
 * and is named in the build log with its share.
 */
fun shippedLanguages(): List<String> {
    val dirs = listOf("src/main/res", "../core/src/main/res").map { file(it) }
    fun names(values: File): Set<String> = values.resolve("strings.xml").takeIf { it.exists() }?.let { f ->
        val root = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).documentElement
        (0 until root.childNodes.length).map { root.childNodes.item(it) }.filterIsInstance<org.w3c.dom.Element>()
            .filter { it.tagName in setOf("string", "plurals") && it.getAttribute("translatable") != "false" }
            .map { it.getAttribute("name") }.toSet()
    }.orEmpty()
    val base = dirs.flatMap { names(it.resolve("values")) }.toSet()
    // values-it, values-pt-rBR, values-b+sr+Latn; not values-night or values-v35.
    val language = Regex("""values-([a-z]{2,3}(-r[A-Z]{2})?|b\+[a-z]{2,3}(\+[A-Za-z0-9]+)*)""")
    val shares = dirs.flatMap { d -> d.listFiles().orEmpty().filter { language.matches(it.name) } }
        .groupBy({ it.name.removePrefix("values-") }, { names(it) })
        .mapValues { (_, sets) -> sets.flatten().toSet().intersect(base).size.toDouble() / base.size }
    shares.filterValues { it < 0.9 }.toSortedMap().forEach { (lang, share) ->
        logger.lifecycle("Translation $lang not shipped: ${Math.round(share * 100)} % of ${base.size} strings (needs 90 %)")
    }
    return listOf("en") + shares.filterValues { it >= 0.9 }.keys.sorted()
}


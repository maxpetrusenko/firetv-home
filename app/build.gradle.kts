import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("app.cash.paparazzi") // test scope only: renders docs/screenshot.png; not part of the APK
}

// Signing config lives outside version control. Point at a properties file (storeFile, storePassword,
// keyAlias, keyPassword) with -PkeystoreProps=/path/keystore.properties (or set keystoreProps in
// ~/.gradle/gradle.properties), or the FIRETV_HOME_KEYSTORE_PROPS environment variable, or put a gitignored
// keystore.properties in the project root. Without any of them the release APK is built unsigned.
val ksProps = Properties().apply {
    val p = providers.gradleProperty("keystoreProps").orNull
        ?: System.getenv("FIRETV_HOME_KEYSTORE_PROPS")
        ?: "${rootDir}/keystore.properties"
    val f = file(p)
    if (f.exists()) f.inputStream().use { load(it) }
}

// Experimental home switching is OFF by default. Enable with -PswitchHome=true.
val switchHome = providers.gradleProperty("switchHome").orNull == "true"

android {
    namespace = "app.firetvhome"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.firetvhome"
        minSdk = 25
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("boolean", "SWITCH_HOME", switchHome.toString())
        if (switchHome) versionNameSuffix = "-switchhome"
    }

    signingConfigs {
        if (ksProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(ksProps.getProperty("storeFile"))
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true } // android.util.Log etc. return defaults

    // A switchhome APK must never land at the default app-release.apk path (the deploy gate uses it).
    if (switchHome) {
        applicationVariants.all {
            outputs.all {
                (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                    "app-$name-switchhome.apk"
            }
        }
    }

    // Default: no-op hook only. Flag on: the switchhome sources, resources and manifest overlay.
    sourceSets {
        getByName("main") {
            if (switchHome) {
                java.srcDir("src/switchhome/java")
                res.srcDir("src/switchhome/res")
            } else {
                java.srcDir("src/plain/java")
            }
        }
        if (switchHome) {
            // Build-type manifests merge over main, which keeps the output path unchanged.
            getByName("debug").manifest.srcFile("src/switchhome/AndroidManifest.xml")
            getByName("release").manifest.srcFile("src/switchhome/AndroidManifest.xml")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013") // android.jar's org.json is a stub on the plain JVM
}

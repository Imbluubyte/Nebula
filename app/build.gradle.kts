plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nebula.studio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nebula.studio"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Two builds from one module:
    //  editor - the studio you install. It carries the player APK (assets/player.apk) and exports games from that.
    //  player - the runtime only (no editor UI, compiler bridge, signing or icons pack), shrunk by R8. Exported games are this.
    flavorDimensions += "role"
    productFlavors {
        create("editor") { dimension = "role" }
        create("player") { dimension = "role" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // Libraries are extracted at install time so the exporter can add libscripts.so as a plain compressed entry.
    packaging { jniLibs { useLegacyPackaging = true } }

    sourceSets {
        getByName("main") {
            java.srcDir("../core/src")        // Android-free engine core, shared with the desktop tests
        }
        getByName("editor") {
            assets.srcDir("../api")           // nebula.sn is shipped in the editor APK (copied out for the builder)
        }
    }
}

// The editor embeds the release player APK as assets/player.apk.
val playerAssets = layout.buildDirectory.dir("generated/playerAssets")
val copyPlayerApk by tasks.registering(Copy::class) {
    dependsOn("assemblePlayerRelease")
    from(layout.buildDirectory.dir("outputs/apk/player/release")) { include("*.apk"); rename { "player.apk" } }
    into(playerAssets)
}
android.sourceSets.getByName("editor").assets.srcDir(playerAssets)
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets") && name.contains("Editor")) dependsOn(copyPlayerApk)
}

dependencies {
    // both builds
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // editor only
    "editorImplementation"(platform("androidx.compose:compose-bom:2024.10.01"))
    "editorImplementation"("androidx.compose.material3:material3")
    "editorImplementation"("androidx.compose.material:material-icons-extended")
    "editorImplementation"("com.android.tools.build:apksig:8.7.3")
}

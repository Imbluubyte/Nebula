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

    buildTypes {
        release { isMinifyEnabled = false }
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
            assets.srcDir("../api")           // nebula.sn is shipped in the APK (copied out for the builder)
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    implementation("com.android.tools.build:apksig:8.7.3")
}

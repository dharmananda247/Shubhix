plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The GitHub build passes the key through environment variables. Nothing secret lives in the code.
val keystorePath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.shubhix.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.shubhix.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "2.1"
        // The GitHub build passes its run number so the app can tell whether a newer release exists.
        buildConfigField("int", "BUILD_NUMBER", System.getenv("BUILD_NUMBER") ?: "0")
    }
    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Lint is advisory here; a lint warning must never stop the APK from building.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

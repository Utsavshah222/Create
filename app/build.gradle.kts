plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jinsolutions.smsforward"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jinsolutions.smsforward"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Token is injected at build time from the GitHub Actions secret GATEWAY_AUTH.
        // It is never stored in source. Locally (no env var) it is just empty.
        val gatewayAuth = System.getenv("GATEWAY_AUTH") ?: ""
        buildConfigField("String", "GATEWAY_AUTH", "\"$gatewayAuth\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

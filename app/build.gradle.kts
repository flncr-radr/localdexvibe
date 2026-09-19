plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.localdex"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.localdex"
        // The Galaxy S23 (One UI 5.1) is the oldest hardware/OS combination this
        // app targets, and it launched on Android 13.
        minSdk = 33
        // Kept at 33 rather than jumping to 34: Android 14 makes a
        // foregroundServiceType mandatory for every foreground service once
        // targeted, and nothing in Android's fixed list of types accurately
        // describes what DexService does (it's not MediaProjection — no
        // MediaProjection API is used here — and none of the others fit either).
        // Targeting exactly 33 still means adopting Android 13's real runtime
        // permission model (POST_NOTIFICATIONS, NEARBY_WIFI_DEVICES) — minSdk 33
        // means every supported device needs it anyway — without that separate,
        // harder migration.
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"

        resourceConfigurations += listOf("en")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // LibADB Android — app-to-own-device ADB over wireless debugging
    implementation("com.github.MuntashirAkon:libadb-android:3.1.0")

    // Custom Conscrypt. Required for pairing: PairingConnectionCtx derives the SPAKE2
    // secret from the TLS exporter, and only this Conscrypt exposes it to apps.
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    // For ADB key/certificate generation
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
}

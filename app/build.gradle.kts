plugins {
    id("com.android.application")
    // Do NOT add org.jetbrains.kotlin.android here — AGP 9.0+ provides built-in Kotlin support
}

android {
    namespace = "com.bobby.docreader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bobby.docreader"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP 9.0+ automatically sets Kotlin JVM target to match compileOptions above
    // No need for kotlinOptions or kotlin { jvmToolchain } block

    buildFeatures {
        viewBinding = false
        compose = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.4")
}
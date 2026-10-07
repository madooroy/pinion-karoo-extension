plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.madooroy.pinionkaroo"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.madooroy.pinionkaroo"
        // Karoo 2 runs Android 8 (API 26); Karoo 3 runs Android 13 (API 33)
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("io.hammerhead:karoo-ext:1.1.9")
    // karoo-ext's inline event consumers decode JSON in the calling module
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    testImplementation("junit:junit:4.13.2")
}

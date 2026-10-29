plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "nl.stacklines.tvambilight"
    compileSdk = 36

    defaultConfig {
        applicationId = "nl.stacklines.tvambilight"
        minSdk = 26
        targetSdk = 34
        versionCode = 52
        versionName = "0.22.0"
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation("com.google.zxing:core:3.5.3")
}

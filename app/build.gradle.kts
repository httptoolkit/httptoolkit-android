plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-parcelize")
}

android {
    namespace = "tech.httptoolkit.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "tech.httptoolkit.android.v1"
        minSdk = 21
        targetSdk = 35
        versionCode = 35
        versionName = "1.5.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["sentryEnabled"] = "false"
        manifestPlaceholders["sentryDsn"] = "null"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            manifestPlaceholders["sentryEnabled"] = "true"
            manifestPlaceholders["sentryDsn"] = "https://6943ce7476d54485a5998ad45289a9bc@sentry.io/1809979"
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }
    lint {
        lintConfig = file("./lint.xml")
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(libs.kotlin.stdlib.jdk7)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.appcompat)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.accompanist.drawablepainter)
    implementation(libs.foundation)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.core.ktx)
    implementation(libs.constraintlayout)
    implementation(libs.localbroadcastmanager)
    implementation(libs.zxing.android.embedded) { isTransitive = false }
    implementation(libs.core)
    implementation(libs.klaxon)
    implementation(libs.okhttp)
    implementation(libs.material)
    implementation(libs.semver)
    implementation(libs.sentry.android)
    implementation(libs.slf4j.nop)
    implementation(libs.play.services.base)
    implementation(libs.installreferrer)
    implementation(libs.swiperefreshlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.runner)
    androidTestImplementation(libs.espresso.core)
}

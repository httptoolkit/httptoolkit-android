plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "tech.httptoolkit.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "tech.httptoolkit.android.v1"
        minSdk  = 21
        targetSdk = 33
        versionCode = 30
        versionName = "1.3.10"

        buildConfigField("String", "SENTRY_DSN", "null")
    }

    buildTypes {
        named("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "SENTRY_DSN", "\"https://6943ce7476d54485a5998ad45289a9bc@sentry.io/1809979\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        lintConfig = file("./lint.xml")
    }

    buildFeatures {
        buildConfig = true
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(libs.androidxAppcompat)
    implementation(libs.androidxConstraintlayout)
    implementation(libs.androidxCoreKtx)
    implementation(libs.androidxLocalbroadcastmanager)
    implementation(libs.installreferrer)
    implementation(libs.klaxon)
    implementation(libs.kotlinReflect)
    implementation(libs.kotlinStdlibJdk7)
    implementation(libs.kotlinxCoroutinesAndroid)
    implementation(libs.kotlinxCoroutinesCore)
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.playServicesBase)
    implementation(libs.semver)
    implementation(libs.sentryAndroid)
    implementation(libs.slf4jNop)
    implementation(libs.zxing)
}

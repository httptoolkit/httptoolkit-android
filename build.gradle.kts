plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.googleServices) apply false
    alias(libs.plugins.kotlinAndroid) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

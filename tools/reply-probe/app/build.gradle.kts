plugins {
    id("com.android.application") version "8.13.2"
}

android {
    namespace = "dev.mooner.lucent.probe"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.mooner.lucent.probe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

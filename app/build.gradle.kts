plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// Firebase 설정 파일이 있을 때만 푸시(FCM)를 켠다. 없으면 앱은 푸시 없이 돌고, 설정 화면이 그렇게 알려 준다(docs/push.md).
// 파일은 빌드 종류마다 따로 둘 수 있다(패키지 이름이 다르다): `src/release/`, `src/debug/`, 또는 둘이 함께 쓰는 `app/` 바로 아래.
// 파일이 없는 빌드 종류는 그 종류의 설정 처리만 끄고 푸시 없이 만든다.
val firebaseConfig = "google-services.json"
val firebaseVariants = listOf("debug", "release").filter { file("src/$it/$firebaseConfig").isFile || file(firebaseConfig).isFile }
if (firebaseVariants.isNotEmpty()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
    tasks.matching { task ->
        task.name.startsWith("process") && task.name.endsWith("GoogleServices") &&
            firebaseVariants.none { task.name.equals("process${it}GoogleServices", ignoreCase = true) }
    }.configureEach { enabled = false }
}

android {
    namespace = "dev.mooner.lucent"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        applicationId = "dev.mooner.lucent"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = libs.versions.versionCode.get().toInt()
        versionName = libs.versions.versionName.get()

        androidResources.localeFilters += listOf("ko", "en")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.bundles.androidx)
    implementation(libs.bundles.kotlin)
    implementation(libs.okhttp)
    implementation(libs.coil)
    implementation(libs.coil.gif)
    implementation(libs.androidx.exifinterface)
    implementation(libs.subsampling.scale.image.view)
    // 잠깐 뜨는 안내(PeekAlert). StarLight와 같은 라이브러리를 그 저장소의 app/libs에서 옮겨 왔다.
    implementation(files("libs/PeekAlert-release.aar"))
    implementation(libs.markwon.core)
    implementation(libs.markwon.inline.parser)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

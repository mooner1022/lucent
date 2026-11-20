// 답장 시험용 앱(Lucent 본체와 따로 짓는다): ../../gradlew -p tools/reply-probe :app:assembleDebug
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

include(":app")

rootProject.name = "LucentReplyProbe"

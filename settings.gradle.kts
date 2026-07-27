pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // 高德 SDK 仓库
        maven { url = uri("https://developer.amap.com/android/maven") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://developer.amap.com/android/maven") }
    }
}

rootProject.name = "cameraupload"
include(":app")
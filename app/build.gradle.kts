plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    compileSdk = 34
    namespace = "com.example.cameraupload"

    defaultConfig {
        applicationId = "com.example.cameraupload"
        minSdk = 24
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    aaptOptions {
        noCompress += "tflite"
    }

    buildFeatures {
        viewBinding = true
    }

    // 确保 jniLibs 目录被正确识别（用于存放 .so 文件，如有需要）
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    val cameraxVersion = "1.3.0"
    implementation("androidx.camera:camera-core:${cameraxVersion}")
    implementation("androidx.camera:camera-camera2:${cameraxVersion}")
    implementation("androidx.camera:camera-lifecycle:${cameraxVersion}")
    implementation("androidx.camera:camera-view:${cameraxVersion}")

    implementation("org.tensorflow:tensorflow-lite:2.13.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("com.google.guava:guava:31.1-android")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.8.22")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.13.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    implementation("com.squareup.okhttp3:okhttp:4.10.0")
    // 高德导航 SDK（jar 文件）
    implementation(files("libs/AMap3DMap_11.1.000_AMapNavi_11.1.000_AMapSearch_9.7.4_AMapLocation_11.1.000_20260303.jar"))
    // 讯飞 SparkChain SDK（aar 文件）
    implementation(files("libs/Codec.aar"))
    implementation(files("libs/SparkChain.aar"))
    implementation(files("libs/AIKit.aar"))

}
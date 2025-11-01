plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.hklab.hkruler"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hklab.hkruler"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures {
        viewBinding = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // OpenCV AAR을 직접 넣는 경우(대체 경로)
    // repositories { flatDir { dirs("libs") } }
    // dependencies { implementation(files("libs/opencv-4.9.0.aar")) }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt",
            "META-INF/license.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt",
            "META-INF/ASL2.0"
        )
    }
}

dependencies {
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.camera:camera-extensions:$camerax")

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OpenCV (Maven 배포본; 문제 시 AAR 방식 사용)
    //implementation("com.quickbirdstudios:opencv:4.9.0")
    implementation("org.opencv:opencv:4.12.0")
}

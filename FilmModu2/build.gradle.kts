plugins {
    id 'com.android.library'
    id 'kotlin-android'
    id 'kotlin-kapt'
}

android {
    compileSdk 34

    defaultConfig {
        minSdk 21
        targetSdk 34
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = '1.8'
    }
}

dependencies {
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'com.github.recloudstream:cloudstream:v3.7.0'
}

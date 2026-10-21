plugins {
    id("com.android.application")
}

android {
    namespace = "cn.enaium.battalion.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.enaium.battalion"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // sdl-kmp's Android AAR ships the JNI bridge for every ABI.
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(project(":"))
    implementation(libs.sdl.kmp.android.jvm)
}

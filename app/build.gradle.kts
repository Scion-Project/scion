plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.dylanneve1.iosonpixel"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.dylanneve1.iosonpixel"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // QEMU ships as an executable named lib*.so; it must be extracted to
        // nativeLibraryDir to be exec()able, so keep legacy (extracted) packaging.
        jniLibs.useLegacyPackaging = true
    }
}

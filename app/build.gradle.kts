plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.dylanneve1.iosonpixel"
    compileSdk = 36
    // For the AVF display's frame copy (src/main/cpp); the same NDK as native/build-android.sh.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "io.github.dylanneve1.iosonpixel"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=none" } }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
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

dependencies {
    // JVM unit tests only (app/src/test); nothing here ships in the APK.
    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is stubs that throw in JVM tests; the real one for InstallMigration.
    testImplementation("org.json:json:20240303")
}

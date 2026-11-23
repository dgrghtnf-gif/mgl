plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pickle.tabletfilemanager"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pickle.tabletfilemanager"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"
        vectorDrawables.useSupportLibrary = false
    }

    // Signing: the release keystore is provided by CI (generated or supplied through a secret).
    // It lives at <repo>/keystore/release.jks and is never committed.
    val releaseKeystore = file("${rootDir}/keystore/release.jks")

    signingConfigs {
        create("release") {
            if (releaseKeystore.exists()) {
                storeFile = releaseKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "pickle1234"
                keyAlias = System.getenv("KEY_ALIAS") ?: "pickle"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "pickle1234"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

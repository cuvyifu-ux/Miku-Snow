plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.miku.snow"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.miku.snow"
        minSdk = 26
        targetSdk = 33
        versionCode = 78
        versionName = "7.6.36"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "DebugProbesKt.bin"
        )
    }
}

dependencies {
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.animation:animation:1.12.0")
    implementation(files("libs/shizuku.jar"))
    implementation("dev.chrisbanes.haze:haze:1.6.10")
}

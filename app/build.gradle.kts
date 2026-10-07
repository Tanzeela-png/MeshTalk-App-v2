plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.meshtalk"
    // ✨ UPDATE: Compile SDK ko 34 kiya taake naye mobiles ke hardware features open hon
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.meshtalk"

        // ✨ UPDATE: Isko 21 (Android 5.0 Lollipop) kiya taake market ke 99% purane mobiles par b install ho jaye
        minSdk = 21

        // ✨ UPDATE: Target SDK 34 karne se Oppo, Vivo, Xiaomi aur Samsung par scanning restrictions khatam ho jayengi
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

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // ✨ UPDATE: Core libraries ko naye SDK ke mutabiq optimize kiya
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
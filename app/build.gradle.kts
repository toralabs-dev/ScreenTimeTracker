plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Load local.properties for signing configuration
val localProperties = java.util.Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

android {
    namespace = "com.screentimetracker.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.screentimetracker.app"
        minSdk = 26  // Android 8.0 - required for UsageStatsManager improvements
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Try environment variables first, then fall back to local.properties
            storeFile = file(
                System.getenv("KEYSTORE_PATH")
                    ?: localProperties.getProperty("KEYSTORE_PATH")
                    ?: "release-keystore.jks"
            )
            storePassword = System.getenv("KEYSTORE_PASSWORD")
                ?: localProperties.getProperty("KEYSTORE_PASSWORD")
                ?: ""
            keyAlias = System.getenv("KEY_ALIAS")
                ?: localProperties.getProperty("KEY_ALIAS")
                ?: "screentimetracker"
            keyPassword = System.getenv("KEY_PASSWORD")
                ?: localProperties.getProperty("KEY_PASSWORD")
                ?: ""
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Enable ViewBinding for type-safe view access
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Material Design 3
    implementation("com.google.android.material:material:1.11.0")

    // ConstraintLayout for flexible layouts
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Lifecycle components (ViewModel, LiveData)
    val lifecycleVersion = "2.7.0"
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")

    // Activity KTX for viewModels() delegate and result APIs
    implementation("androidx.activity:activity-ktx:1.8.2")

    // Fragment KTX (for future use with fragments)
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Room database for local persistence
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // WorkManager for background tasks
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Browser Custom Tabs for opening privacy policy
    implementation("androidx.browser:browser:1.7.0")

    // MPAndroidChart for usage trend visualization
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // Testing dependencies
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

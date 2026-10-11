plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Set by the release workflow from the tag (v1.2.3 -> 1.2.3); local builds are "dev".
val releaseVersion: String? = providers.gradleProperty("releaseVersion").orNull

android {
    namespace = "com.jamieduncan.acetag"
    compileSdk = 35
    // The pod image ships build-tools 35.0.0 only, read-only; AGP 8.7 would otherwise ask for 34.
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.jamieduncan.acetag"
        minSdk = 26
        targetSdk = 35
        // versionCode must rise with every release: 1.2.3 -> 10203.
        versionCode = releaseVersion
            ?.split(".")?.map(String::toInt)
            ?.let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }
            ?: 1
        versionName = releaseVersion ?: "dev"
    }

    // The release key comes from the environment (the release workflow decodes it from secrets).
    // Without it, assembleRelease produces an unsigned APK; debug builds never need it.
    val keystore = System.getenv("ACETAG_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("ACETAG_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ACETAG_KEY_ALIAS")
                keyPassword = System.getenv("ACETAG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
        viewBinding = true
    }
}

// Room writes each schema version here at build time. Commit it: the migration test checks that
// migrating an old database ends exactly at the newest exported schema.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in unit tests (throws at runtime) — pull in the real impl.
    testImplementation("org.json:json:20240303")
    // A real SQLite on the JVM, so migrations run against a database instead of being read.
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
}

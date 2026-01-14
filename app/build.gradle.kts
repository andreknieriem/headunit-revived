import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
}

dependencies {
    implementation("com.google.protobuf:protobuf-java:3.25.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.media:media:1.6.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.startup:startup-runtime:1.1.1")
    // ViewModel and LiveData
    implementation("androidx.lifecycle:lifecycle-extensions:2.2.0")
    kapt("androidx.lifecycle:lifecycle-compiler:2.6.2")

    // KTX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")

    testImplementation("junit:junit:4.13.2")

    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.0")
    implementation(project(":contract"))

    // Multidex
    implementation("androidx.multidex:multidex:2.0.1")

    // Navigation Component
    implementation("androidx.navigation:navigation-fragment-ktx:2.3.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.3.5")
}

android {
    compileSdk = 34
    namespace = "com.andrerinas.headunitrevived"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.andrerinas.headunitrevived"
        minSdk = 16
        targetSdk = 34
        versionCode = 24
        versionName = "1.6.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = true
    }

    signingConfigs {
        getByName("debug") {
            // storeFile = file("../keystore.jkc")
            // storePassword = property("HEADUNIT_KEYSTORE_PASSWORD") as String
            // keyAlias = property("HEADUNIT_KEY_ALIAS") as String
            // keyPassword = property("HEADUNIT_KEY_PASSWORD") as String
        }

        create("release") {
            storeFile = file("../headunit-release-key.jks") // Use your new keystore file name
            storePassword = System.getenv("HEADUNIT_KEYSTORE_PASSWORD")
            keyAlias = "headunit-revived" // Replace with your key alias
            keyPassword = System.getenv("HEADUNIT_KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-project.txt")
            signingConfig = signingConfigs.getByName("release")
            multiDexKeepProguard = file("multidex-config.pro")
        }
        getByName("debug") {
            isDebuggable = true
            isJniDebuggable = true
            multiDexKeepProguard = file("multidex-config.pro")
        }
    }

    lint {
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        (this as KotlinJvmOptions).let {
           it.jvmTarget = "1.8"
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                var outputFileName = "${variant.applicationId}_${variant.versionName}_debug.apk"
                if(variant.buildType.name == "release") {
                    outputFileName = "${variant.applicationId}_${variant.versionName}.apk"
                    output.outputFileName = outputFileName
                }
                output.outputFileName = outputFileName
            }
    }
}

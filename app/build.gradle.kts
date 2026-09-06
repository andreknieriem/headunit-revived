import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions
import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
}

android {
    compileSdk = 34
    ndkVersion = "29.0.14206865"
    namespace = "com.andrerinas.openheadunit"

    buildFeatures {
        buildConfig = true
        aidl = true // needed for shizuku
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    val copyRootAssets = tasks.register<Copy>("copyRootAssets") {
        from("${project.rootDir}/CHANGELOG.md", "${project.rootDir}/LICENSE")
        into("${project.layout.buildDirectory.get().asFile}/generated/assets/root")
    }

    // Scan available locales at configuration time and store as BuildConfig field
    val resDir = file("src/main/res")
    val availableLocales = resDir.listFiles { file ->
        file.isDirectory && file.name.startsWith("values-") &&
        // Filter out non-language qualifiers (night mode, screen size, etc.)
        !file.name.contains("night") &&
        !file.name.contains("land") &&
        !file.name.contains("port") &&
        !file.name.matches(Regex("values-[whsml]\\d+.*")) &&
        !file.name.matches(Regex("values-v\\d+")) &&
        // Check that it contains strings.xml (actual translation)
        file.resolve("strings.xml").exists()
    }?.map { dir ->
        // Extract locale code from directory name (e.g., "values-es" -> "es", "values-pt-rBR" -> "pt-rBR")
        dir.name.removePrefix("values-")
    }?.sorted() ?: emptyList()

    println("Detected available locales: $availableLocales")

    // Which commit an APK was built from. versionName and versionCode do not move between two
    // candidates of the same fix, so they cannot identify a build; this can. A "-dirty" suffix
    // means the tree had uncommitted changes, which is the other thing worth knowing.
    val gitDescription: String = try {
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf("git") + args)
                .directory(rootDir)
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().readText().trim()
            return if (process.waitFor() == 0) text else ""
        }
        val sha = git("rev-parse", "--short=12", "HEAD")
        when {
            sha.isEmpty() -> "unknown"
            git("status", "--porcelain").isNotEmpty() -> "$sha-dirty"
            else -> sha
        }
    } catch (e: Exception) {
        "unknown"
    }

    println("Building from commit: $gitDescription")

    sourceSets {
        getByName("main") {
            assets.srcDirs("${project.layout.buildDirectory.get().asFile}/generated/assets/root")
        }
    }

    tasks.withType<com.android.build.gradle.tasks.MergeSourceSetFolders>().configureEach {
        dependsOn(copyRootAssets)
    }

    tasks.configureEach {
        if (name.contains("lint", ignoreCase = true)) {
            dependsOn(copyRootAssets)
        }
    }

    defaultConfig {
        applicationId = "com.sesam.emzoomaa"
        minSdk = 16
        targetSdk = 28
        versionCode = 107
        versionName = "1.7.0(3.3.1)"
        setProperty("archivesBaseName", "Emzoom AA_v${versionName}")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = true
        vectorDrawables.useSupportLibrary = true

        // Store available locales in BuildConfig for runtime access
        // This is scanned at build time from values-XX directories
        buildConfigField("String", "AVAILABLE_LOCALES", "\"${availableLocales.joinToString(",")}\"")
        buildConfigField("String", "GIT_SHA", "\"$gitDescription\"")

        externalNativeBuild {
            cmake {
                cppFlags("")
            }
        }
    }

    flavorDimensions.add("distribution")
    productFlavors {
        create("playstore") {
            dimension = "distribution"
            minSdk = 21
            manifestPlaceholders["appLabel"] = "@string/title"
            buildConfigField("Boolean", "OPTIMIZE_ULTRAWIDE", "false")
        }
        create("emzoom") {
            dimension = "distribution"
            applicationId = "com.sesam.emzoomaa"
            manifestPlaceholders["appLabel"] = "Emzoom AA"
            buildConfigField("Boolean", "OPTIMIZE_ULTRAWIDE", "true")
        }
    }

    signingConfigs {
        getByName("debug") {
            // storeFile = file("../keystore.jkc")
            // storePassword = property("HEADUNIT_KEYSTORE_PASSWORD") as String
            // keyAlias = property("HEADUNIT_KEYSTORE_ALIAS") as String
            // keyPassword = property("HEADUNIT_KEYSTORE_PASSWORD") as String
        }
        create("release") {
            storeFile = rootProject.file("Sesam.jks")
            storePassword = "737266"
            keyAlias = "key0"
            keyPassword = "737266"
            isV1SigningEnabled = true
            isV2SigningEnabled = true

            val keyfile = rootProject.file("key.properties")
            if (keyfile.exists()) {
                val keyprops = Properties()
                keyprops.load(FileInputStream(keyfile))

                if (keyprops.containsKey("storeFile")) {
                    val storePath = keyprops.getProperty("storeFile")
                    storeFile = rootProject.file(storePath)
                }
                if (keyprops.containsKey("storePassword")) storePassword = keyprops.getProperty("storePassword")
                if (keyprops.containsKey("keyAlias")) keyAlias = keyprops.getProperty("keyAlias")
                if (keyprops.containsKey("keyPassword")) keyPassword = keyprops.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-project.txt"
            )
            signingConfig = signingConfigs.getByName("release")
        }

        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/notice.txt"
            excludes += "META-INF/ASL2.0"
            excludes += "META-INF/*.kotlin_module"
        }
    }

    lint {
        abortOnError = false
        disable += "PackagedPrivateKey"
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
                var outputFileName = "Emzoom AA_v${variant.versionName}_debug.apk"
                if (variant.buildType.name == "release") {
                    outputFileName = "Emzoom AA_v${variant.versionName}.apk"
                }
                output.outputFileName = outputFileName
            }
    }

    // Auto-save Emzoom Debug APK to Desktop as "Emzoom AA.apk"
    tasks.register<Copy>("saveEmzoomToDesktop") {
        group = "distribution"
        from(layout.buildDirectory.dir("outputs/apk/emzoom/debug"))
        include("*.apk")
        into(File("/Users/hussamselmy/Desktop"))
        rename { "Emzoom AA.apk" }
        dependsOn("assembleEmzoomDebug")
    }

    // Save Emzoom Release APK to Desktop
    tasks.register<Copy>("saveEmzoomReleaseToDesktop") {
        group = "distribution"
        from(layout.buildDirectory.dir("outputs/apk/emzoom/release"))
        include("*.apk")
        into(File("/Users/hussamselmy/Desktop"))
        dependsOn("assembleEmzoomRelease")
    }
}

dependencies {
    // Conscrypt (Flavor specific: 2.6.1 for Playstore 16KB alignment; 2.5.3 for Github minSdk 16)
    "playstoreImplementation"("org.conscrypt:conscrypt-android:2.6.1")
    "emzoomImplementation"("org.conscrypt:conscrypt-android:2.5.3")

    implementation("com.google.protobuf:protobuf-java:3.25.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.media:media:1.6.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.startup:startup-runtime:1.1.1")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    // ViewModel and LiveData
    implementation("androidx.lifecycle:lifecycle-extensions:2.2.0")
    kapt("androidx.lifecycle:lifecycle-compiler:2.6.2")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:4.1.0")

    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.0")
    implementation(project(":contract"))

    // Multidex
    implementation("androidx.multidex:multidex:2.0.1")

    // Navigation Component
    implementation("androidx.navigation:navigation-fragment-ktx:2.3.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.3.5")

    // DexMaker for runtime subclassing (Hotspot Fix)
    implementation("com.linkedin.dexmaker:dexmaker:2.28.3")

    // Glide for image/GIF loading (custom loading screen)
    implementation("com.github.bumptech.glide:glide:4.16.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")

    // ZXing for QR Code generation
    implementation("com.google.zxing:core:3.5.3")

    // Shizuku for root / shell access
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
}

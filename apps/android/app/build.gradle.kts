import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The release workflow passes the version; local and gate builds use the
// development version. Settings shows the name, and Android compares the code.
val glimpseVersionCode = providers.gradleProperty("glimpse.versionCode").map(String::toInt).orElse(1)
val glimpseVersionName = providers.gradleProperty("glimpse.versionName").orElse("dev")

// The release workflow decrypts the keystore and passes its location and
// passwords through these variables. Without them the release build stays
// unsigned, which is what the quality gate builds.
val releaseKeystore = providers.environmentVariable("GLIMPSE_KEYSTORE_FILE")

android {
    namespace = "io.github.davidvornholt.glimpse"
    compileSdk = 37
    // Without this AGP picks its own default, which the Nix SDK doesn't have.
    // Keep it in step with dev-shell.local.nix.
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.github.davidvornholt.glimpse"
        minSdk = 31
        targetSdk = 37
        versionCode = glimpseVersionCode.get()
        versionName = glimpseVersionName.get()
    }

    signingConfigs {
        if (releaseKeystore.isPresent) {
            create("release") {
                storeFile = file(releaseKeystore.get())
                storePassword = providers.environmentVariable("GLIMPSE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("GLIMPSE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("GLIMPSE_KEY_PASSWORD").get()
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        localeFilters += listOf("en", "de")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = true
        lintConfig = file("lint.xml")
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.animation.ExperimentalSharedTransitionApi",
        )
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.core.splashscreen)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.camera.mlkit.vision)
    implementation(libs.mlkit.barcode)
    implementation(libs.mlkit.document.scanner)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.exifinterface)
    implementation(libs.graphics.shapes)
    implementation(libs.profileinstaller)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.mockito)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.work.testing)
}

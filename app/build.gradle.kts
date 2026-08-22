import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing key lives outside the repo entirely (~/.android/keystores/palewake/) so it
// can never end up in git, even by accident. Every release build must be signed with this same
// key — a debug-signed or differently-keyed APK can't install as an update over a real release,
// only uninstall/reinstall, which is exactly what release distribution can't tolerate.
val releaseKeystoreProps = Properties().apply {
    val propsFile = File(System.getProperty("user.home"), ".android/keystores/palewake/palewake-release.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "suck.alot.palewake"
    compileSdk = 35

    defaultConfig {
        applicationId = "suck.alot.palewake"
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = "1.5"
    }

    signingConfigs {
        create("release") {
            if (releaseKeystoreProps.containsKey("storeFile")) {
                storeFile = file(releaseKeystoreProps.getProperty("storeFile"))
                storePassword = releaseKeystoreProps.getProperty("storePassword")
                keyAlias = releaseKeystoreProps.getProperty("keyAlias")
                keyPassword = releaseKeystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystoreProps.containsKey("storeFile")) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

tasks.matching { it.name.startsWith("assembleRelease") || it.name.startsWith("bundleRelease") }.configureEach {
    doFirst {
        require(releaseKeystoreProps.containsKey("storeFile")) {
            "Missing release keystore config at ~/.android/keystores/palewake/palewake-release.properties " +
                "— release builds must be signed with the real release key, never the debug key."
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
}

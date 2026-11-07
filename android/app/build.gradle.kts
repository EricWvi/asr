plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The release tag sets the version: -PappVersion=1.2.3. 1.2.3 is code 1002003, so codes
// grow with versions; a pre-release shares its code with the release it leads to.
val appVersion = providers.gradleProperty("appVersion").getOrElse("0.1.0")
val appVersionCode = appVersion.substringBefore('-').split('.').map(String::toInt)
    .let { (major, minor, patch) -> major * 1_000_000 + minor * 1_000 + patch }
val updateUrl = providers.gradleProperty("updateUrl")
    .getOrElse("https://github.com/EricWvi/asr/releases/latest/download/latest.json")
// Android only installs an update signed like the installed app, so CI always signs with
// this key. Local release builds without it use the debug key.
val keystore = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull

android {
    namespace = "top.onlyquant.tingxie"
    compileSdk = 37

    defaultConfig {
        applicationId = "top.onlyquant.tingxie"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
    }

    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    testOptions {
        // OkHttp sees android.jar in unit tests and logs through it.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.browser)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.markdown.m3)
    implementation(libs.markdown.coil3)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

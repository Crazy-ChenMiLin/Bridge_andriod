plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Base64

// ── 标签驱动版本号（仅在 CI 推送 v* tag 时生效）─────────────────────
// 版本名：去掉 tag 的 "v" 前缀（如 v0.1.1 -> 0.1.1）。
// 版本码：由 semver 计算，单调递增（major*10000 + minor*100 + patch）。
//   例：v0.1.1 -> 101；v1.2.3 -> 10203。
fun tagVersionName(refName: String): String? {
    if (!refName.startsWith("v")) return null
    val v = refName.removePrefix("v")
    return if (v.matches(Regex("""\d+\.\d+\.\d+"""))) v else null
}

fun tagVersionCode(versionName: String): Int {
    val parts = versionName.split(".")
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    return major * 10000 + minor * 100 + patch
}

val githubRefName = System.getenv("GITHUB_REF_NAME") ?: ""
val derivedVersionName = tagVersionName(githubRefName)

android {
    namespace = "xyz.chenmilin.ankimcpbridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "xyz.chenmilin.ankimcpbridge"
        minSdk = 26
        targetSdk = 34
        versionCode = derivedVersionName?.let { tagVersionCode(it) } ?: 2
        versionName = derivedVersionName ?: "0.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 仅在提供签名密钥（GitHub Secrets）时启用签名，否则产出未签名 APK
            val keystoreBase64 = System.getenv("ANDROID_KEYSTORE_BASE64")
            if (!keystoreBase64.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    signingConfigs {
        create("release") {
            val keystoreBase64 = System.getenv("ANDROID_KEYSTORE_BASE64")
            if (!keystoreBase64.isNullOrBlank()) {
                val keystoreFile = project.layout.buildDirectory.file("release-key.jks").get().asFile
                keystoreFile.writeBytes(Base64.getDecoder().decode(keystoreBase64))
                storeFile = keystoreFile
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: ""
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
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }

    lint {
        disable += "MissingTranslation"
        disable += "ExtraTranslation"
        abortOnError = true
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.01.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Ktor Server
    implementation("io.ktor:ktor-server-core:2.3.7")
    implementation("io.ktor:ktor-server-netty:2.3.7")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-server-tests:2.3.7")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

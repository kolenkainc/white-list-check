import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("io.sentry.android.gradle")
}

val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val releaseKeystoreFile = releaseKeystorePath?.let { rootProject.file(it) }?.takeIf { it.exists() }
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = keystorePropsFile.takeIf { it.exists() }?.reader()?.use {
    Properties().apply { load(it) }
}
val propsKeystoreFile = keystoreProps?.getProperty("storeFile")?.trim()?.takeIf { it.isNotEmpty() }
    ?.let { rootProject.file(it) }
    ?.takeIf { it.exists() }

val localPropsForSentry = rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use {
    Properties().apply { load(it) }
}
val sentryDsn = System.getenv("SENTRY_DSN")?.trim()?.takeIf { it.isNotEmpty() }
    ?: localPropsForSentry?.getProperty("sentry.dsn")?.trim()?.takeIf { it.isNotEmpty() }
    ?: (rootProject.findProperty("sentry.dsn") as String?)?.trim()?.takeIf { it.isNotEmpty() }
    ?: ""

val ingestToken = System.getenv("INGEST_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }
    ?: localPropsForSentry?.getProperty("ingest.token")?.trim()?.takeIf { it.isNotEmpty() }
    ?: (rootProject.findProperty("ingest.token") as String?)?.trim()?.takeIf { it.isNotEmpty() }
    ?: ""

val appApplicationId = "tech.romashov.whitelistcheck"
val appVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
val appVersionName = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0"
/** Должен совпадать с version в шаге Sentry в .github/workflows/android.yml */
val sentryRelease = "$appApplicationId@$appVersionName+$appVersionCode"

val (githubOwner, githubRepo) = run {
    val envFull = System.getenv("GITHUB_REPOSITORY")
    if (!envFull.isNullOrBlank() && "/" in envFull) {
        val i = envFull.indexOf("/")
        Pair(envFull.substring(0, i), envFull.substring(i + 1))
    } else {
        val o = (rootProject.findProperty("github.owner") as String?)?.trim()?.takeIf { it.isNotEmpty() }
            ?: "YOUR_GITHUB_OWNER"
        val r = (rootProject.findProperty("github.repo") as String?)?.trim()?.takeIf { it.isNotEmpty() }
            ?: "white-list-check"
        Pair(o, r)
    }
}

android {
    namespace = "tech.romashov.whitelistcheck"
    compileSdk = 35

    defaultConfig {
        applicationId = appApplicationId
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "GITHUB_OWNER", "\"${githubOwner.replace("\"", "\\\"")}\"")
        buildConfigField("String", "GITHUB_REPO", "\"${githubRepo.replace("\"", "\\\"")}\"")
        buildConfigField("String", "SENTRY_DSN", "\"${sentryDsn.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField(
            "String",
            "SENTRY_RELEASE",
            "\"${sentryRelease.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )
        buildConfigField(
            "String",
            "INGEST_TOKEN",
            "\"${ingestToken.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )
    }

    signingConfigs {
        when {
            releaseKeystoreFile != null -> {
                create("releaseUpload") {
                    storeFile = releaseKeystoreFile
                    storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("RELEASE_KEY_ALIAS").orEmpty()
                    keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                }
            }
            keystoreProps != null && propsKeystoreFile != null -> {
                create("releaseUpload") {
                    storeFile = propsKeystoreFile
                    storePassword = keystoreProps.getProperty("storePassword")
                    keyAlias = keystoreProps.getProperty("keyAlias").orEmpty()
                    keyPassword = keystoreProps.getProperty("keyPassword")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("releaseUpload")
                ?: signingConfigs.getByName("debug")
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
        buildConfig = true
    }
}

sentry {
    autoInstallation {
        sentryVersion.set("7.22.4")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

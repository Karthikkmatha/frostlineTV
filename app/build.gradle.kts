import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---- Secrets: read from a KEY=VALUE file, never printed. Only key NAMES appear in errors. ----
val secretsPath: String = providers.gradleProperty("frostline.secretsFile")
    .orElse("""C:\Users\karth\Documents\repos\frosty-tv\.env""").get()

fun parseEnv(file: File): Map<String, String> = file.readLines()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
    .associate { line ->
        val l = line.removePrefix("export ").trim()
        val i = l.indexOf('=')
        l.substring(0, i).trim() to l.substring(i + 1).trim().removeSurrounding("\"").removeSurrounding("'")
    }

val secretsFile = File(secretsPath)
if (!secretsFile.isFile) {
    throw GradleException(
        "FrostlineTV: secrets file not found at '$secretsPath'. Create it (KEY=VALUE lines with the Twitch " +
            "client id and secret) or pass -Pfrostline.secretsFile=<path>.",
    )
}
val env = parseEnv(secretsFile)
fun firstNonEmpty(vararg keys: String): String = keys.firstNotNullOfOrNull { k -> env[k]?.takeIf { it.isNotBlank() } } ?: ""
val clientId = firstNonEmpty("CLIENT_ID", "TWITCH_CLIENT_ID")
val clientSecret = firstNonEmpty("SECRET", "CLIENT_SECRET", "TWITCH_CLIENT_SECRET", "TWITCH_SECRET")
if (clientId.isEmpty() || clientSecret.isEmpty()) {
    throw GradleException(
        "FrostlineTV: the secrets file is missing a non-empty " +
            (if (clientId.isEmpty()) "client id (CLIENT_ID or TWITCH_CLIENT_ID) " else "") +
            (if (clientSecret.isEmpty()) "client secret (SECRET, CLIENT_SECRET or TWITCH_CLIENT_SECRET) " else "") +
            "entry. Keys present in the file: ${env.keys.sorted()}",
    )
}
fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "tv.frostline.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "tv.frostline.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        buildConfigField("String", "TWITCH_CLIENT_ID", q(clientId))
        buildConfigField("String", "TWITCH_CLIENT_SECRET", q(clientSecret))
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        create("debugKeystore") {
            storeFile = File(System.getProperty("user.home"), ".android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debugKeystore")
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":core:twitch"))
    implementation(project(":core:twitch-internal"))
    implementation(project(":feature:home"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:player"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
}

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
// Short commit hash of the build: app/commit.txt (written when the sources are synced to the build machine, which has no .git).
val commitShort: String = File(projectDir, "commit.txt").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } ?: "dev"
fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "tv.frostline.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "tv.frostline.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 6
        versionName = "0.6.0"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        buildConfigField("String", "GIT_HASH", q(commitShort))
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
        // Same as release but shrunk with R8 and proguard-rules.pro: checks that the keep rules cover serialization, Retrofit and Media3.
        create("releaseMinified") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            matchingFallbacks += "release"
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    // Installs the baseline profile (assets/dexopt/baseline.prof) on first launch so the app starts AOT-compiled, not interpreted.
    implementation(libs.androidx.profileinstaller)
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":core:twitch"))
    implementation(project(":core:twitch-internal"))
    implementation(project(":feature:home"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:player"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:channel"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.coil.gif)
}

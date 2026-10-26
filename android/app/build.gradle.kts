import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val vpVersion = providers.environmentVariable("VP_VERSION").getOrElse("1.0.0").removePrefix("v").removePrefix("android-v")
val vpCode = providers.environmentVariable("VP_VERSION_CODE").getOrElse("1").toInt()
// Welche Stimmen eingebaut sind, entscheidet tools/fetch-voices.py (komplett | kompakt); hier nur der Name fürs Paket.
val vpSet = providers.environmentVariable("VP_VOICE_SET").getOrElse("komplett")

android {
    namespace = "de.honeybalecta.vorlesepult"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.honeybalecta.vorlesepult"
        minSdk = 29
        targetSdk = 36
        versionCode = vpCode
        versionName = "$vpVersion-$vpSet"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Signaturschlüssel kommt aus den Repository-Secrets (siehe Workflow); ohne Schlüssel wird mit Debug-Signatur gebaut
    signingConfigs {
        create("release") {
            val ks = providers.environmentVariable("VP_KEYSTORE").orNull
            if (ks != null) {
                storeFile = file(ks)
                storePassword = providers.environmentVariable("VP_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("VP_KEY_ALIAS").getOrElse("vorlesepult")
                keyPassword = providers.environmentVariable("VP_KEY_PASSWORD").orNull
                    ?: providers.environmentVariable("VP_KEYSTORE_PASSWORD").get()
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (providers.environmentVariable("VP_KEYSTORE").isPresent)
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Modelle unkomprimiert ablegen: schnelleres Laden, kein Entpacken im Speicher
    androidResources { noCompress += listOf("onnx", "bin") }
    packaging {
        jniLibs { useLegacyPackaging = false }
        resources { excludes += listOf("META-INF/*.kotlin_module") }
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    // Sprach-Engine (sherpa-onnx) und Aussprache (piper-phonemize) als AAR aus den GitHub-Releases, siehe Workflow
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
}

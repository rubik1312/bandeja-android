import java.util.Properties

plugins {
    id("com.android.application")
}

// La clave de firma vive fuera del repositorio (keystore.properties + keystore/).
// Sin la misma clave, Android no deja instalar una versión nueva encima de la anterior.
val firma = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

dependencies {
    // Motor para "Oye Jarvis" (modelos de openWakeWord en assets/oww).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    // Reconocimiento en español para "jarvis" pronunciado con J española (modelo en assets/vosk-es).
    implementation("com.alphacephei:vosk-android:0.3.75")
}

android {
    namespace = "io.github.rubik1312.bandeja"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.rubik1312.bandeja"
        minSdk = 30
        targetSdk = 37
        // Sube versionCode en cada versión nativa nueva y publícala con el tag "v<versionCode>".
        versionCode = 6
        versionName = "1.5"
        // Solo celulares de 64 bits (como el S22): el motor de voz pesa mucho por cada arquitectura.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("release") {
            if (firma.isNotEmpty()) {
                storeFile = rootProject.file(firma.getProperty("storeFile"))
                storePassword = firma.getProperty("storePassword")
                keyAlias = firma.getProperty("keyAlias")
                keyPassword = firma.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

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
    implementation("ai.picovoice:porcupine-android:4.0.2")
}

android {
    namespace = "io.github.rubik1312.bandeja"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.rubik1312.bandeja"
        minSdk = 30
        targetSdk = 37
        // Sube versionCode en cada versión nativa nueva y publícala con el tag "v<versionCode>".
        versionCode = 2
        versionName = "1.1"
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

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.orbitacasa.web"
    compileSdk = 34

    defaultConfig {
        applicationId = "it.orbitacasa.web"
        minSdk = 29
        targetSdk = 34
        // Il workflow passa -PversionCode=<numero build>, così ogni APK è più nuovo del precedente
        val vc = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionCode = vc
        versionName = "1.0.$vc"
    }

    // La chiave di firma NON è nel repository: arriva da variabili d'ambiente
    // (su GitHub Actions dai Secrets, vedi .github/workflows/build.yml)
    val ksFile = System.getenv("ORBITA_KEYSTORE_FILE")
    signingConfigs {
        if (ksFile != null && file(ksFile).exists()) {
            create("orbita") {
                storeFile = file(ksFile)
                storePassword = System.getenv("ORBITA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ORBITA_KEY_ALIAS")
                keyPassword = System.getenv("ORBITA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Senza chiave l'APK esce non firmato (non installabile)
            signingConfigs.findByName("orbita")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}

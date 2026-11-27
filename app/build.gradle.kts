plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.com.coffeelabs.ninho"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.coffeelabs.ninho"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0-teste"
    }

    // Chave só de teste (não é a chave de publicação da CoffeeLabs).
    signingConfigs {
        create("teste") {
            storeFile = file("teste.keystore")
            storePassword = "ninhoteste"
            keyAlias = "ninho"
            keyPassword = "ninhoteste"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("teste")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("teste")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

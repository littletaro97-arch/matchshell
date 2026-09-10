plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hcgy2018.site"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hcgy2018.site"
        minSdk = 26
        targetSdk = 35
        // 版本号映射：major*100 + minor*10 + patch，例如 1.0.0 -> 100、1.1.2 -> 112
        versionCode = providers.gradleProperty("VERSION_CODE").orElse("112").get().toInt()
        versionName = providers.gradleProperty("VERSION_NAME").orElse("1.1.2").get()
        buildConfigField(
            "String",
            "UPDATE_MANIFEST_URL",
            "\"${providers.gradleProperty("UPDATE_MANIFEST_URL").orElse("https://littletaro97-arch.github.io/matchshell/updates/stable.json").get()}\""
        )
        buildConfigField(
            "String",
            "UPDATE_PUBLIC_KEY_BASE64",
            "\"${providers.gradleProperty("UPDATE_PUBLIC_KEY_BASE64").orElse("MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAqHRgATPmsz9zflWYrg0orcEudqyjTb07laTcV5GOCxttP9xR55wBi7DWF5Zs+rAe1jM2cJ6a3Knp69I2PutBop+YO3AReJpD8Ph4wCy/pNXQkw6ZStYtihwM9LegIBFzYL2mE8PoSJQmzoDe/jOEbAUCSTqzmUBw2wixNDuu2FjKW3dEMja0zlGo8zFeBW1WpDSzKsFagYTBk2AsZKcYW2/vm1aZ+bH2II9wPIow311z/PZhX4RHNZOJNAdXD1uhEuKh6mvLhToWwFgGvucCYZjguCVNy2fBIMN8jggU47Btc6D6eGhnnQLd73xkn/xvDkamUwTAo78d3ZxOLpuqJddb4ys0md2GtBQTgAVoHSiKmbJMwxvQwTsKSeFB2HNQuB8+lILSLG/jaJYd9SrB9zXObU63oL1gPYvaijSg5DBtXnybAZj/VLwf0MtHjAy4JeNbI7Jp+pqQf/fPcvpiW3ozZ2unw7OwOcuMLdlGvg8ZFsy+AQo+sZNz5BWJR5BBAgMBAAE=").get()}\""
        )
    }

    flavorDimensions += "pdfEngine"
    productFlavors {
        create("pdf") {
            dimension = "pdfEngine"
            resValue("string", "app_name", "火柴公益")
            buildConfigField("boolean", "HAS_PDF_CONVERTER", "true")
            ndk { abiFilters += "arm64-v8a" }
        }
    }

    signingConfigs {
        create("release") {
            val signingFile = providers.environmentVariable("MATCHSHELL_KEYSTORE_FILE")
                .orElse("C:/Users/LittleTaro/.android/matchshell-release.keystore")
            storeFile = file(signingFile.get())
            storePassword = providers.environmentVariable("MATCHSHELL_KEYSTORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("MATCHSHELL_KEY_ALIAS").orElse("matchshell").get()
            keyPassword = providers.environmentVariable("MATCHSHELL_KEY_PASSWORD").orNull
        }
    }

    buildTypes {
        debug {
            // 调试包允许局域网明文 HTTP，便于手机联调
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (providers.environmentVariable("MATCHSHELL_KEYSTORE_PASSWORD").isPresent &&
                providers.environmentVariable("MATCHSHELL_KEY_PASSWORD").isPresent
            ) {
                signingConfig = signingConfigs.getByName("release")
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

    // AGP 8 起默认开启；显式写出便于后续排查 R 类问题。
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.media3:media3-transformer:1.11.0")
    implementation("androidx.media3:media3-effect:1.11.0")
    implementation("androidx.media3:media3-common:1.11.0")
}

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------- 签名配置
// 口令从 local.properties 读取（该文件不入库），也可用同名环境变量。
// 没有密钥或没配口令时退回默认签名，保证别人 clone 下来也能构建。
val localProps = Properties()
val localFile = rootProject.file("local.properties")
if (localFile.exists()) localFile.inputStream().use { localProps.load(it) }

val ksFile = file("../_tools/keystore/fleshout.jks")
val ksPass = localProps.getProperty("FLESHOUT_KEYSTORE_PASSWORD")
    ?: System.getenv("FLESHOUT_KEYSTORE_PASSWORD") ?: ""
val keyAliasName = localProps.getProperty("FLESHOUT_KEY_ALIAS") ?: "fleshout"
val keyPass = localProps.getProperty("FLESHOUT_KEY_PASSWORD") ?: ksPass
val hasSigning = ksFile.exists() && ksPass.isNotEmpty()

android {
    namespace = "com.fleshout.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fleshout.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 19
        versionName = "0.3.8"
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = ksFile
                storePassword = ksPass
                keyAlias = keyAliasName
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf("META-INF/*.kotlin_module")
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.core:core-ktx:1.13.1")
}

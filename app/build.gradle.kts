plugins {
    id("com.android.application")
}

android {
    namespace = "com.zeekrmate.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zeekrmate.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

val apkVersionName = android.defaultConfig.versionName
    ?: error("android.defaultConfig.versionName is required to name the APK")
base.archivesName.set("ZeekrMate-$apkVersionName")

androidComponents {
    onVariants { variant ->
        val assembleName = "assemble${variant.name.replaceFirstChar { it.uppercase() }}"
        val apkDir = layout.buildDirectory.dir("outputs/apk/${variant.name}")
        val versionName = variant.outputs.first().versionName
        tasks.matching { it.name == assembleName }.configureEach {
            doLast {
                val name = versionName.orNull
                    ?: error("versionName is required to name the APK")
                val dir = apkDir.get().asFile
                val dest = dir.resolve("ZeekrMate-$name.apk")
                val source = dir.listFiles()
                    ?.filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
                    ?.firstOrNull { it.name != dest.name }
                if (source != null && source.canonicalFile != dest.canonicalFile) {
                    source.copyTo(dest, overwrite = true)
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}

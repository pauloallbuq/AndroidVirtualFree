plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.androidvirtualfree"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.androidvirtualfree"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    // Executáveis/so precisam ser extraídos para nativeLibraryDir (única pasta executável sem root)
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
    // Disco qcow2 e ROMs não devem ser recomprimidos de forma diferente
    androidResources { noCompress += listOf("qcow2") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests.all {
            it.maxHeapSize = "2g"
            // Teste ponta-a-ponta do convidado (só roda quando AVF_E2E_ISO está definido)
            listOf("AVF_E2E_ISO", "AVF_E2E_DATA", "AVF_E2E_PROBE_APK", "AVF_E2E_OUT", "AVF_E2E_ACCEL", "AVF_E2E_QEMU", "AVF_E2E_CPU", "AVF_E2E_NOREBOOT", "AVF_E2E_CMDLINE_EXTRA")
                .forEach { k -> System.getenv(k)?.let { v -> it.environment(k, v) } }
            it.testLogging { events("passed", "failed", "skipped", "standardOut", "standardError"); showStandardStreams = true }
        }
    }
}

dependencies {
    implementation("dev.mobile:dadb:2.0.0")
    testImplementation("junit:junit:4.13.2")
}

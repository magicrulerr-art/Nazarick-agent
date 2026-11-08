plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.localdream"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // The core engine is a SUBPROCESS binary, not a JNI lib: it lives in jniLibs purely
        // because that is the one packaging slot Android installs as a real file under
        // nativeLibraryDir with the execute bit intact (ProcessBuilder cannot exec out of the
        // APK). AGP would otherwise strip it from a release build's native libs via the
        // abi/toolchain checks; keep it whole.
        jniLibs.keepDebugSymbols += "**/libstable_diffusion_core.so"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":ai"))
    // PngEncoder (hand-rolled PNG container, already fleet-proven on the sd-cpp lane) and
    // ModelInstall's local-models directory contract both come from neighbours, not copies.
    implementation(project(":sd-cpp"))
    implementation(project(":local-llm"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}

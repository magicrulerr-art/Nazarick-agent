plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.sdcpp"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The JNI contract for SdRaster; see consumer-rules.pro for why R8's
        // release shrink otherwise deletes the ctor the native side caches.
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                // GPU offload for image generation (owner directive, 2026-11-04:
                // "images should go to npu, like the off-grid repo"). sd.cpp's pinned
                // tree has no QNN backend — the Adreno GPU is the tier this engine can
                // reach today, and the house doctrine says measure the flag-flip tier
                // before paying for an NPU port. OpenCL, not Vulkan: llama-cpp's lane
                // learned on THIS Fold that the Adreno driver faults on Vulkan
                // (llama-cpp/build.gradle.kts:23) while OpenCL ran 25/25 layers at
                // 376 tok/s prefill. The wiring (Khronos headers + soname stub, and
                // why the dial is SDCPP_ENABLE_OPENCL rather than SD_OPENCL, which
                // this module's CMakeLists FORCEs) lives in src/main/cpp/CMakeLists.txt.
                // Escape hatch: -Psd.gpu=cpu.
                // 2026-11-07: the pinned ggml fork DOES carry an NPU tier —
                // ggml-hexagon (HTP over FastRPC, arch v73-v81; the Fold's SM8650
                // is v73). The stale "no QNN backend" note above predates the
                // audit in floor/hexagon-npu-plan-2026-11-07.md. Third dial
                // value: -Psd.gpu=hexagon compiles the HTP backend and leaves
                // OpenCL OUT, so both backends' GPU-class devices can never
                // contend for auto-fit (Hexagon registers as DEVICE_TYPE_GPU,
                // ggml-hexagon.cpp:9275). CI supplies HEXAGON_SDK_ROOT; the
                // skel build FATALs without it, deliberately loudly.
                val sdGpu = providers.gradleProperty("sd.gpu").getOrElse("opencl")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DSDCPP_ENABLE_OPENCL=${if (sdGpu == "opencl") "ON" else "OFF"}",
                    "-DSDCPP_ENABLE_HEXAGON=${if (sdGpu == "hexagon") "ON" else "OFF"}",
                )
                if (sdGpu == "hexagon") {
                    val hexSdk = providers.environmentVariable("HEXAGON_SDK_ROOT").orNull
                    require(!hexSdk.isNullOrBlank()) {
                        "sd.gpu=hexagon requires HEXAGON_SDK_ROOT env (CI fetches the " +
                        "snapdragon-toolchain community SDK; the htp skel build dies without it)"
                    }
                    arguments += "-DHEXAGON_SDK_ROOT=$hexSdk"
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            # Was 3.22.1; the ggml fork's htp skel project (built via
            # ExternalProject with THIS very cmake binary) declares
            # cmake_minimum_required(3.22.2) -- run 120: "You are running
            # version 3.22.1-g37088a8". 3.31.6 is the sdkmanager-current
            # series AGP installs on demand; upstream hexagon CI is on 4.x.
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Both native modules bundle the same shared C++ runtime; keep ours.
        jniLibs.pickFirsts += "**/libc++_shared.so"
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
    implementation(project(":local-llm"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

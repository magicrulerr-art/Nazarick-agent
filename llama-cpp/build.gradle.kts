plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.llamacpp"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                // C++17 and RTTI/exceptions are required: common links nlohmann/json
                // and a Jinja implementation, both of which throw.
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                // GPU backend selector: -Pllama.gpu=cpu|vulkan|opencl.
                // Vulkan is the portable path and this branch's experiment default;
                // OpenCL is the Adreno-tuned A/B variant. cpu = upstream behavior.
                val llamaGpu = providers.gradleProperty("llama.gpu").getOrElse("vulkan")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DLLAMAJNI_ENABLE_OPENCL=${if (llamaGpu == "opencl") "ON" else "OFF"}",
                    "-DLLAMAJNI_ENABLE_VULKAN=${if (llamaGpu == "vulkan") "ON" else "OFF"}",
                )
                // CI installs a SPIRV-Headers CMake package for the Vulkan build (see
                // .github/workflows/build.yml); the NDK's find-root mode would otherwise
                // hide it, so package lookups are opened to both sysroot and prefix.
                System.getenv("SPIRV_HEADERS_PREFIX")?.let {
                    arguments += listOf(
                        "-DCMAKE_PREFIX_PATH=$it",
                        "-DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH",
                    )
                }
                // FindVulkan resolves glslc (the offline shader compiler ggml needs) from
                // the desktop Vulkan SDK, which the NDK toolchain hides; CI installs
                // glslang-tools and exports the path. Local SDK installs keep working
                // untouched because the variable is simply absent there.
                System.getenv("GLSLC")?.let {
                    arguments += "-DVulkan_GLSLC_EXECUTABLE=$it"
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // llama.cpp ships its own copies for tooling; keep only ours.
        jniLibs.pickFirsts += "**/libc++_shared.so"
    }

    testOptions {
        // diag branch only: the provider's moxw-perf breadcrumbs call android.util.Log,
        // which throws "not mocked" in JVM unit tests against the stub android.jar.
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
    // Real org.json impl for JVM unit tests: the stub android.jar throws "not mocked" on
    // every JSONObject call, and ChatRequestMapper builds its request with org.json to
    // keep one JSON object model in that file.
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

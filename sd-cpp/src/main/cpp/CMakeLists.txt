# sd.cpp as an Android library, built the way :llama-cpp builds llama.cpp: engine as a static
# subproject, one shared JNI leaf, nothing else from the upstream tree compiled.
cmake_minimum_required(VERSION 3.22.1)
project(sdcpp LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Forced, not inherited. Every option here is OFF upstream when added as a subproject, but
# "upstream's default" is exactly the kind of fact that changes under us: a flipped default
# would start fetching libwebp or looking for a Vulkan SDK inside an APK build. The list is
# the option set as of the pinned commit -- sd.cpp has no SD_CURL/SD_BUILD_CLI, so don't
# "tidy" one in without checking it exists.
set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_WEBP OFF CACHE BOOL "" FORCE)
set(SD_USE_SYSTEM_WEBP OFF CACHE BOOL "" FORCE)
set(SD_WEBM OFF CACHE BOOL "" FORCE)
set(SD_USE_SYSTEM_WEBM OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_GGML_LIB OFF CACHE BOOL "" FORCE)
set(SD_USE_SYSTEM_GGML OFF CACHE BOOL "" FORCE)
set(SD_USE_UPSTREAM_GGML OFF CACHE BOOL "" FORCE)
set(SD_CUDA OFF CACHE BOOL "" FORCE)
set(SD_HIPBLAS OFF CACHE BOOL "" FORCE)
set(SD_METAL OFF CACHE BOOL "" FORCE)
set(SD_VULKAN OFF CACHE BOOL "" FORCE)
set(SD_OPENCL OFF CACHE BOOL "" FORCE)
set(SD_SYCL OFF CACHE BOOL "" FORCE)
set(SD_MUSA OFF CACHE BOOL "" FORCE)

add_subdirectory(native/sd.cpp)

add_library(sdcpp_jni SHARED
        sdcpp_jni.cpp
)

target_link_libraries(sdcpp_jni PRIVATE
        stable-diffusion
        log
)

# Two ggml's live in this APK: llama.cpp's inside libllamajni.so, sd.cpp's patched fork inside
# libsdcpp_jni.so. They are different builds with different headers, and Android's linker
# interposes exported symbols between libraries by load order -- which is a bug that appears
# only when both engines run in one process, i.e. never in CI and always in production.
# Hiding everything from the static archives leaves only the JNI entry points exported, so
# neither engine can see the other's ggml. Same guard llama.cpp should eventually get.
target_link_options(sdcpp_jni PRIVATE "LINKER:--exclude-libs,ALL")
set_target_properties(sdcpp_jni PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON
)

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

# GPU offload for image generation (owner directive, 2026-11-04: "images should go to
# npu, like the off-grid repo"). The Gradle side passes -DSDCPP_ENABLE_OPENCL; the
# dial lives HERE because every flag above is FORCE — a raw -DSD_OPENCL=ON would be
# squashed by this very file. OpenCL not Vulkan: llama-cpp's lane crowned OpenCL on
# THIS Fold (Adreno driver faults on Vulkan; see llama-cpp/src/main/cpp/CMakeLists.txt).
# sd.cpp's patched ggml fork calls find_package(OpenCL REQUIRED), and NDK sysroots
# carry neither CL/cl.h nor libOpenCL.so — the same hole :llama-cpp plugged, so the
# proven recipe is copied: Khronos headers at configure time plus a do-nothing stub
# carrying the libOpenCL.so soname; the vendor ICD resolves cl* symbols at load time.
option(SDCPP_ENABLE_OPENCL "Build sd.cpp with the Adreno OpenCL backend" OFF)
if(SDCPP_ENABLE_OPENCL AND ANDROID AND NOT OpenCL_INCLUDE_DIR)
    include(FetchContent)
    set(SDCPP_OPENCL_HEADERS_TAG "v2026.05.29")
    FetchContent_Declare(sdcpp_opencl_headers
        URL "https://github.com/KhronosGroup/OpenCL-Headers/archive/refs/tags/${SDCPP_OPENCL_HEADERS_TAG}.zip")
    FetchContent_Populate(sdcpp_opencl_headers)
    if(EXISTS "${sdcpp_opencl_headers_SOURCE_DIR}/CL/cl.h")
        set(OpenCL_INCLUDE_DIR "${sdcpp_opencl_headers_SOURCE_DIR}" CACHE PATH "" FORCE)
        file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/sdcpp_opencl_stub.cpp"
             "// Carries the libOpenCL.so soname; the ICD resolves symbols at load time.\n")
        add_library(sdcpp_opencl_stub SHARED "${CMAKE_CURRENT_BINARY_DIR}/sdcpp_opencl_stub.cpp")
        set_target_properties(sdcpp_opencl_stub PROPERTIES
            OUTPUT_NAME "OpenCL"
            LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}")
        set(OpenCL_LIBRARY "${CMAKE_CURRENT_BINARY_DIR}/libOpenCL.so" CACHE FILEPATH "" FORCE)
    else()
        message(WARNING "sdcpp: OpenCL headers fetch missed — building without OpenCL")
        set(SDCPP_ENABLE_OPENCL OFF)
    endif()
endif()
if(SDCPP_ENABLE_OPENCL)
    set(SD_OPENCL ON CACHE BOOL "" FORCE)
endif()

# Three levels up from src/main/cpp/, i.e. the module root -- same shape :llama-cpp uses.
# Spelling it "native/sd.cpp" resolves against this file's directory and CI says, quite
# correctly, that no such directory exists.
set(SD_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../native/sd.cpp)

add_subdirectory(${SD_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/sd.cpp)

if(SDCPP_ENABLE_OPENCL AND TARGET sdcpp_opencl_stub)
    # FindOpenCL gives the stub no build-order edge and OpenCL::OpenCL consumers may
    # resolve it wrong; wire both explicitly (llamajni pattern, lines 142-157 there).
    if(TARGET OpenCL::OpenCL)
        set_property(TARGET OpenCL::OpenCL PROPERTY IMPORTED_LOCATION
            "${CMAKE_CURRENT_BINARY_DIR}/libOpenCL.so")
        set_property(TARGET OpenCL::OpenCL APPEND PROPERTY INTERFACE_LINK_DIRECTORIES
            "${CMAKE_CURRENT_BINARY_DIR}")
    endif()
    foreach(_sdcpp_opencl_consumer ggml ggml-opencl stable-diffusion)
        if(TARGET ${_sdcpp_opencl_consumer})
            add_dependencies(${_sdcpp_opencl_consumer} sdcpp_opencl_stub)
        endif()
    endforeach()
    # The NDK links every shared lib with --no-undefined. sd-cpp builds its ggml
    # static (SD_BUILD_SHARED_GGML_LIB=OFF), so the cl* references surface at the
    # libsdcpp_jni.so link — run 105 proved that applying the lazy treatment one
    # layer down (as llamajni does for its separate libggml-opencl.so) does not
    # reach the leaf: ld.lld listed every cl* there. The leaf must carry it, and
    # link the stub explicitly so libOpenCL.so lands in DT_NEEDED and the vendor
    # ICD is what the loader resolves at dlopen time.
    target_link_options(sdcpp_jni PRIVATE
        "LINKER:--unresolved-symbols=ignore-all"
        "LINKER:-z,lazy")
    target_link_libraries(sdcpp_jni PRIVATE ${OpenCL_LIBRARIES})
    add_dependencies(sdcpp_jni sdcpp_opencl_stub)
endif()

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

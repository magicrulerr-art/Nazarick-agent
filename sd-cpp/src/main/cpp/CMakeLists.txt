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

# Hexagon HTP (NPU) backend (owner directive 2026-11-07: "Do qnn, since GPU is
# not working" -- after the OpenCL verdict in floor/opencl-fold-verdict-2026-11-07.md:
# Adreno ran, +32% slower AND white pixels; the plan of record is
# floor/hexagon-npu-plan-2026-11-07.md). Same FORCE-dial rationale as
# SDCPP_ENABLE_OPENCL above: every backend flag here is forced OFF. Turning
# GGML_HEXAGON on pulls ggml/src/ggml-hexagon, which FATALs unless
# HEXAGON_SDK_ROOT points at a real SDK (build_idl + hexagon-clang build the
# libggml-htp-vXX DSP skels; CI fetches the snapdragon-toolchain Community
# Edition). The loud failure is the point -- a silently-CPU "NPU" build is the
# exact bite this repo already took during the pre-OpenCL era. Mutual
# exclusion with OpenCL is enforced on the gradle side (sd.gpu picks one), so
# only one GPU-class device exists for auto-fit.
option(SDCPP_ENABLE_HEXAGON "Build sd.cpp with the Hexagon HTP (NPU) backend" OFF)
if(SDCPP_ENABLE_HEXAGON)
    if(NOT EXISTS "${HEXAGON_SDK_ROOT}")
        message(FATAL_ERROR
            "sdcpp: SDCPP_ENABLE_HEXAGON needs -DHEXAGON_SDK_ROOT=<extracted Hexagon SDK> "
            "(got '${HEXAGON_SDK_ROOT}')")
    endif()
    set(GGML_HEXAGON ON CACHE BOOL "" FORCE)
    # SDK 6.7's hexagon_fun.cmake runs string(FIND ${PREBUILT_LIB_DIR} toolv8X..)
    # at include time -- with the variable empty the first argument vanishes and
    # every check dies ("string sub-command FIND requires 3 or 4 parameters",
    # run 119). Upstream dodges this only because its docker presets export the
    # var. The checks feed a QURT-only libc++ branch we never take (host side is
    # Android); only the derived include dirs matter and the real ones are
    # already passed explicitly by ggml-hexagon/CMakeLists. android_aarch64 is
    # the ship dir that actually exists in this SDK for the FastRPC host side.
    set(PREBUILT_LIB_DIR "android_aarch64" CACHE STRING
        "Hexagon SDK ship dir (see run 119 note above)" FORCE)
    # NO libcdsprpc link lane (run 126 device proof, the honest correction):
    # htp-drv.cpp dlopens "libcdsprpc.so" itself at runtime and dlsyms every
    # FastRPC fn (ggml-hexagon/htp-drv.cpp:337-375), so link-time symbols were
    # never needed. The earlier llamajni-style soname stub put libcdsprpc.so in
    # DT_NEEDED -- and the app classloader namespace cannot see /vendor libs
    # even though the vendor lib is in public.libraries.txt (that grants
    # dlopen-by-soname only). Result: dlopen(libsdcpp_jni.so) died
    # UnsatisfiedLinkError before JNI_OnLoad ever ran.
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
    # ICD is what the loader resolves at dlopen time. (Applied after add_library
    # below — run 106 named the ordering: a target does not exist before its
    # add_library call, and CMake is merciless about foreknowledge.)
endif()

add_library(sdcpp_jni SHARED
        sdcpp_jni.cpp
)

target_link_libraries(sdcpp_jni PRIVATE
        stable-diffusion
        log
)

if(SDCPP_ENABLE_OPENCL AND TARGET sdcpp_opencl_stub)
    target_link_options(sdcpp_jni PRIVATE
        "LINKER:--unresolved-symbols=ignore-all"
        "LINKER:-z,lazy")
    target_link_libraries(sdcpp_jni PRIVATE ${OpenCL_LIBRARIES})
    add_dependencies(sdcpp_jni sdcpp_opencl_stub)
endif()

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

# The FastRPC loader resolves the DSP skel by FILENAME at runtime
# ("file:///libggml-htp-v73.so"), it is not a DT_NEEDED dep, and Gradle never
# runs ggml's install() step -- so the skel must be steered into jniLibs while
# externalNativeBuild is still executing, or AGP's jniLibs merge never sees it
# and the device logs "failed to load skel" in a language nobody prints.
# v73 = the Fold's SM8650; the tree also produces v75/v79/v81 we cannot run.
if(SDCPP_ENABLE_HEXAGON)
    target_compile_definitions(sdcpp_jni PRIVATE SDCPP_HAVE_HEXAGON=1)
    # ignore-all stays REQUIRED (static ggml-hexagon + htp_iface glue carry
    # FastRPC refs; every real call routes through htp-drv's dlsym'd pfn
    # table, so nothing is ever demanded at load). NO stub, NO DT_NEEDED --
    # see the run 126 note above the add_library for why the OpenCL recipe
    # cannot be copied for FastRPC.
    target_link_options(sdcpp_jni PRIVATE
        "LINKER:--unresolved-symbols=ignore-all"
        "LINKER:-z,lazy")
    add_custom_command(TARGET sdcpp_jni POST_BUILD
        COMMAND ${CMAKE_COMMAND}
            -DSEARCH_DIR=${CMAKE_BINARY_DIR}
            -DDST_DIR=${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a
            -DSKEL=libggml-htp-v73.so
            -P ${CMAKE_CURRENT_SOURCE_DIR}/copy_htp_skel.cmake
        COMMENT "sdcpp: packaging libggml-htp-v73 skel into jniLibs")
    add_dependencies(sdcpp_jni htp-v73)
endif()

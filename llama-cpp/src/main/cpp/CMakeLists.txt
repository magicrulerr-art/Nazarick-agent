cmake_minimum_required(VERSION 3.22.1)
project(llamajni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(LLAMAJNI_ENABLE_OPENCL "Build the OpenCL backend for Adreno GPUs" OFF)
option(LLAMAJNI_ENABLE_VULKAN "Build the Vulkan backend (portable GPU path)" OFF)

set(LLAMA_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../native/llama.cpp)

# Everything we do not need. Each of these adds build time or binary size.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
# common carries chat templates, grammar-constrained tool calling and parsing.
set(LLAMA_BUILD_COMMON   ON  CACHE BOOL "" FORCE)

set(GGML_OPENMP    OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

if(LLAMAJNI_ENABLE_OPENCL)
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
    # NDK r28 sysroots carry neither CL/cl.h nor libOpenCL.so. Fetch Khronos'
    # official headers at configure time; if the fetch misses, degrade quietly
    # to CPU (the app discovers backends at runtime and must not die on it).
    if(ANDROID AND NOT OpenCL_INCLUDE_DIR)
        include(FetchContent)
        set(LLAMAJNI_OPENCL_HEADERS_TAG "v2026.05.29")
        FetchContent_Declare(llamajni_opencl_headers
            URL "https://github.com/KhronosGroup/OpenCL-Headers/archive/refs/tags/${LLAMAJNI_OPENCL_HEADERS_TAG}.zip")
        FetchContent_Populate(llamajni_opencl_headers)
        if(EXISTS "${llamajni_opencl_headers_SOURCE_DIR}/CL/cl.h")
            set(OpenCL_INCLUDE_DIR "${llamajni_opencl_headers_SOURCE_DIR}" CACHE PATH "" FORCE)
            # The real libOpenCL.so is provided at runtime by the GPU driver's
            # ICD. Link against a do-nothing stub library carrying the right
            # soname instead — a self-referencing linker script crashes lld.
            # CXX-only project: a .c stub would demand the unenabled C language.
            file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/llamajni_opencl_stub.cpp"
                 "// Carries the libOpenCL.so soname; the ICD resolves symbols at load time.\n")
            add_library(llamajni_opencl_stub SHARED "${CMAKE_CURRENT_BINARY_DIR}/llamajni_opencl_stub.cpp")
            set_target_properties(llamajni_opencl_stub PROPERTIES
                OUTPUT_NAME "OpenCL"
                LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}")
            set(OpenCL_LIBRARY "${CMAKE_CURRENT_BINARY_DIR}/libOpenCL.so" CACHE FILEPATH "" FORCE)
        else()
            message(WARNING "llamajni: OpenCL headers fetch missed — building without OpenCL")
            set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
        endif()
    endif()
endif()

if(LLAMAJNI_ENABLE_VULKAN)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    # The NDK sysroot carries the C Vulkan headers only, but ggml-vulkan's
    # sources include <vulkan/vulkan.hpp> — the C++ bindings ship with nothing
    # Android-side. Fetch a matched Khronos pair (C headers + HPP, same tag)
    # ahead of the sysroot so vulkan.h and vulkan.hpp agree on header version.
    if(ANDROID AND NOT LLAMAJNI_VULKAN_HEADERS_READY)
        include(FetchContent)
        set(LLAMAJNI_VULKAN_TAG "v1.4.365")
        FetchContent_Declare(llamajni_vulkan_headers
            URL "https://github.com/KhronosGroup/Vulkan-Headers/archive/refs/tags/${LLAMAJNI_VULKAN_TAG}.zip")
        FetchContent_Declare(llamajni_vulkan_hpp
            URL "https://github.com/KhronosGroup/Vulkan-Hpp/archive/refs/tags/${LLAMAJNI_VULKAN_TAG}.zip")
        # ggml-vulkan-types.h also drinks from <spirv/unified1/spirv.hpp>; on
        # desktop that rides along with the Vulkan SDK, which Android lacks.
        FetchContent_Declare(llamajni_spirv_headers
            URL "https://github.com/KhronosGroup/SPIRV-Headers/archive/refs/tags/vulkan-sdk-1.4.363.0.zip")
        FetchContent_Populate(llamajni_vulkan_headers)
        FetchContent_Populate(llamajni_vulkan_hpp)
        FetchContent_Populate(llamajni_spirv_headers)
        if(EXISTS "${llamajni_vulkan_headers_SOURCE_DIR}/include/vulkan/vulkan.h"
           AND EXISTS "${llamajni_vulkan_hpp_SOURCE_DIR}/vulkan/vulkan.hpp"
           AND EXISTS "${llamajni_spirv_headers_SOURCE_DIR}/include/spirv/unified1/spirv.hpp")
            set(LLAMAJNI_VULKAN_HEADERS_READY ON CACHE BOOL "" FORCE)
            include_directories(BEFORE
                "${llamajni_vulkan_headers_SOURCE_DIR}/include"
                "${llamajni_vulkan_hpp_SOURCE_DIR}"
                "${llamajni_spirv_headers_SOURCE_DIR}/include")
        else()
            message(WARNING "llamajni: Vulkan headers fetch layout unexpected — building without Vulkan")
            set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
        endif()
    endif()
endif()

add_subdirectory(${LLAMA_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

# ggml's find_package(Vulkan) grabs the runner's HOST libvulkan.so under our
# relaxed find-root mode — an x86_64 library lld silently ignores, so every
# vk* symbol goes undefined. Hand it the NDK's ABI-correct import stub by
# derived path; the device loader (API 26+) provides the real library.
if(TARGET ggml-vulkan)
    if(ANDROID)
        string(TOLOWER "${CMAKE_HOST_SYSTEM_NAME}-x86_64" LLAMAJNI_HOST_DIR)
        if(ANDROID_ABI STREQUAL "arm64-v8a")
            set(_llamajni_abi_triple "aarch64-linux-android")
        else()
            set(_llamajni_abi_triple "${ANDROID_ABI}-linux-android")
        endif()
        # NDK stores import stubs in per-API dirs; the minSdk-26 one predates
        # Vulkan 1.1 exports (vkGetPhysicalDeviceFeatures2 et al). Link the
        # newest stub the NDK carries — real symbol checking, NEEDED=libvulkan.so,
        # and every phone we support ships a newer loader than that stub.
        # CMAKE_ANDROID_NDK proved unset under this AGP; trust CMAKE_SYSROOT.
        set(_llamajni_vk_stub_dir "")
        foreach(_llamajni_root "${CMAKE_SYSROOT}" "${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/${LLAMAJNI_HOST_DIR}/sysroot")
            if(EXISTS "${_llamajni_root}/usr/lib/${_llamajni_abi_triple}")
                set(_llamajni_vk_stub_dir "${_llamajni_root}/usr/lib/${_llamajni_abi_triple}")
                break()
            endif()
        endforeach()
        if(_llamajni_vk_stub_dir)
            file(GLOB _llamajni_vk_api_dirs RELATIVE "${_llamajni_vk_stub_dir}" "${_llamajni_vk_stub_dir}/[0-9]*")
            if(_llamajni_vk_api_dirs)
                list(SORT _llamajni_vk_api_dirs COMPARE NATURAL ORDER DESCENDING)
                list(GET _llamajni_vk_api_dirs 0 _llamajni_vk_api)
                set(_llamajni_vulkan_stub_lib "${_llamajni_vk_stub_dir}/${_llamajni_vk_api}/libvulkan.so")
            elseif(EXISTS "${_llamajni_vk_stub_dir}/libvulkan.so")
                set(_llamajni_vulkan_stub_lib "${_llamajni_vk_stub_dir}/libvulkan.so")
            endif()
        endif()
    endif()
    if(_llamajni_vulkan_stub_lib)
        message(STATUS "llamajni: linking ggml-vulkan against ${_llamajni_vulkan_stub_lib}")
        target_link_libraries(ggml-vulkan PRIVATE "${_llamajni_vulkan_stub_lib}")
    else()
        message(WARNING "llamajni: no NDK libvulkan stub found — expecting FindVulkan's")
    endif()
    # Android's loader (and every ICD behind it) exports the full Vulkan core
    # the shaders use, but the NDK import stubs omit several 1.1+ entry points
    # (vkGetPhysicalDeviceFeatures2 & friends). Let them bind lazily at load —
    # same treatment as the OpenCL lane; NDK's --no-undefined otherwise balks.
    target_link_options(ggml-vulkan PRIVATE
        "LINKER:--unresolved-symbols=ignore-all"
        "LINKER:-z,lazy")
endif()

# ggml links via the ${OpenCL_LIBRARIES} string (full path → -lOpenCL -L…);
# belt-and-braces the same wiring for anything consuming OpenCL::OpenCL, and
# an explicit build-order edge for the stub — FindOpenCL gives it none.
if(TARGET OpenCL::OpenCL AND TARGET llamajni_opencl_stub)
    set_property(TARGET OpenCL::OpenCL PROPERTY IMPORTED_LOCATION
        "${CMAKE_CURRENT_BINARY_DIR}/libOpenCL.so")
    set_property(TARGET OpenCL::OpenCL APPEND PROPERTY INTERFACE_LINK_DIRECTORIES
        "${CMAKE_CURRENT_BINARY_DIR}")
endif()
foreach(_llamajni_opencl_consumer ggml ggml-opencl)
    if(TARGET ${_llamajni_opencl_consumer} AND TARGET llamajni_opencl_stub)
        add_dependencies(${_llamajni_opencl_consumer} llamajni_opencl_stub)
    endif()
endforeach()

# The NDK links shared libs with --no-undefined; our stub deliberately carries
# no symbols — the vendor ICD supplies cl* at load time. Let them stay lazy.
if(TARGET ggml-opencl)
    target_link_options(ggml-opencl PRIVATE
        "LINKER:--unresolved-symbols=ignore-all"
        "LINKER:-z,lazy")
endif()

add_library(llamajni SHARED llama_jni.cpp)

target_include_directories(llamajni PRIVATE
    ${LLAMA_ROOT}/include
    ${LLAMA_ROOT}/ggml/include
    ${LLAMA_ROOT}/common
)

find_library(ANDROID_LOG_LIB log)

target_link_libraries(llamajni PRIVATE llama llama-common ${ANDROID_LOG_LIB})

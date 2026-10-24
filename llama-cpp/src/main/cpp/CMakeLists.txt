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
                EXCLUDE_FROM_ALL ON)  # link-time only — must not ride in the APK and shadow the driver's lib
            set(OpenCL_LIBRARY "${CMAKE_CURRENT_BINARY_DIR}/libOpenCL.so" CACHE FILEPATH "" FORCE)
        else()
            message(WARNING "llamajni: OpenCL headers fetch missed — building without OpenCL")
            set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
        endif()
    endif()
endif()

if(LLAMAJNI_ENABLE_VULKAN)
    # ggml-vulkan dlopens libvulkan.so at runtime; no extra Android linking needed.
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
endif()

add_subdirectory(${LLAMA_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

# The OpenCL stub is reached only through FindOpenCL's imported target, which
# carries no build-order edge; declare it for anything that may link the file.
foreach(_llamajni_opencl_consumer ggml ggml-opencl)
    if(TARGET ${_llamajni_opencl_consumer} AND TARGET llamajni_opencl_stub)
        add_dependencies(${_llamajni_opencl_consumer} llamajni_opencl_stub)
    endif()
endforeach()

add_library(llamajni SHARED llama_jni.cpp)

target_include_directories(llamajni PRIVATE
    ${LLAMA_ROOT}/include
    ${LLAMA_ROOT}/ggml/include
    ${LLAMA_ROOT}/common
)

find_library(ANDROID_LOG_LIB log)

target_link_libraries(llamajni PRIVATE llama llama-common ${ANDROID_LOG_LIB})

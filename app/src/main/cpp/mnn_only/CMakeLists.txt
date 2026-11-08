cmake_minimum_required(VERSION 3.18)
project(localdream_mnn_only LANGUAGES C CXX)

# Dedicated Android MNN SD1.5 native runtime. No Qualcomm SDK or QNN binaries.
# Keep original mixed QNN/MNN CMake target untouched.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT ANDROID)
  message(FATAL_ERROR "Compile this runtime using the Android NDK toolchain")
endif()
set(NATIVE_ROOT "${CMAKE_CURRENT_LIST_DIR}/..")
set(THIRD "${NATIVE_ROOT}/3rdparty")
foreach(required MNN tokenizers-cpp json cpp-httplib stb xtensor xtl xsimd xtensor-blas zstd)
  if(NOT EXISTS "${THIRD}/${required}")
    message(FATAL_ERROR "Missing submodule ${required}: git submodule update --init --recursive")
  endif()
endforeach()

set(MNN_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(MNN_LOW_MEMORY ON CACHE BOOL "" FORCE)
set(MNN_BUILD_FOR_ANDROID ON CACHE BOOL "" FORCE)
set(MNN_BUILD_FOR_ANDROID_COMMAND ON CACHE BOOL "" FORCE)
set(MNN_USE_LOGCAT OFF CACHE BOOL "" FORCE)
set(MNN_OPENCL ON CACHE BOOL "" FORCE)
set(MNN_SUPPORT_TRANSFORMER_FUSE ON CACHE BOOL "" FORCE)
set(MNN_SEP_BUILD OFF CACHE BOOL "" FORCE)
add_subdirectory("${THIRD}/MNN" "${CMAKE_BINARY_DIR}/mnn" EXCLUDE_FROM_ALL)

set(ZSTD_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_STATIC ON CACHE BOOL "" FORCE)
set(ZSTD_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(ZSTD_MULTITHREAD_SUPPORT OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory("${THIRD}/zstd/build/cmake" "${CMAKE_BINARY_DIR}/zstd" EXCLUDE_FROM_ALL)

set(MSGPACK_BUILD_DOCS OFF CACHE BOOL "" FORCE)
add_subdirectory("${THIRD}/tokenizers-cpp" "${CMAKE_BINARY_DIR}/tokenizers" EXCLUDE_FROM_ALL)

add_executable(libstable_diffusion_core.so "${CMAKE_CURRENT_SOURCE_DIR}/main_mnn.cpp")
target_compile_options(libstable_diffusion_core.so PRIVATE -O3 -Wall -Wextra -fPIC)
target_compile_definitions(libstable_diffusion_core.so PRIVATE XTENSOR_USE_XSIMD)
target_include_directories(libstable_diffusion_core.so PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${NATIVE_ROOT}/src"
    "${THIRD}/MNN/include"
    "${THIRD}/json/include"
    "${THIRD}/json/include/nlohmann"
    "${THIRD}/cpp-httplib"
    "${THIRD}/stb"
    "${THIRD}/xtensor/include"
    "${THIRD}/xtl/include"
    "${THIRD}/xsimd/include"
    "${THIRD}/xtensor-blas/include"
    "${THIRD}/zstd/lib"
)
target_link_libraries(libstable_diffusion_core.so PRIVATE
    MNN tokenizers_cpp libzstd log android dl m c GLESv2 EGL
)
set_target_properties(libstable_diffusion_core.so PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin/arm64-v8a"
)

cmake_minimum_required(VERSION 3.22.1)
project(iboard_whisper)

# whisper.cpp v1.7.6 is checked out into ./whisper.cpp by CI (see .github/workflows/build.yml).
set(CMAKE_CXX_STANDARD 17)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_DIR}/ggml)
FetchContent_MakeAvailable(ggml)

add_library(iboard_whisper SHARED
    ${WHISPER_DIR}/src/whisper.cpp
    whisper_jni.cpp)

target_include_directories(iboard_whisper PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/src
    ${WHISPER_DIR}/ggml/include)
target_compile_definitions(iboard_whisper PUBLIC GGML_USE_CPU)
target_compile_options(iboard_whisper PRIVATE -O3 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(iboard_whisper PRIVATE -Wl,--gc-sections)

find_library(LOG_LIB log)
target_link_libraries(iboard_whisper ${LOG_LIB} android ggml)

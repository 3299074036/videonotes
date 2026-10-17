# videonotes 本地 LLM 的 native 构建（M7a）。
#
# 引入 workspace 级 dependencies 下的 llama.cpp 源码（只读，不修改），
# 只编译 core（llama + ggml），关闭 tests / examples / tools / server / common。
#
# 注意：本目录是 app/src/main/cpp。按 AGENTS.md 铁律 1，不得擅自新增目录类型——
# cpp/ 是 Android Gradle 插件约定的标准 native 源码目录，非新增类型。

cmake_minimum_required(VERSION 3.22.1)

project(videonotes_llama)

# llama.cpp 源码位置：允许用环境变量覆盖，默认指向 workspace 依赖目录。
# 相对路径：app/src/main/cpp → 上 6 级到 7Z1H → dependencies/...
if(DEFINED ENV{LLAMA_CPP_DIR} AND NOT "$ENV{LLAMA_CPP_DIR}" STREQUAL "")
    set(LLAMA_CPP_DIR "$ENV{LLAMA_CPP_DIR}")
else()
    set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../dependencies/llama.cpp/llama.cpp-0.7.0")
endif()

if(NOT EXISTS "${LLAMA_CPP_DIR}/include/llama.h")
    message(FATAL_ERROR "找不到 llama.cpp 源码：${LLAMA_CPP_DIR}（可设 LLAMA_CPP_DIR 环境变量覆盖）")
endif()

# 只编 core：关掉所有附带目标（默认在 standalone 构建时会全开）
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_CPP_DIR}" llama.cpp-build)

add_library(videonotes_llama SHARED llama_jni.cpp)

# llama target 的 PUBLIC include 目录（含 llama.h）会自动传递
target_link_libraries(videonotes_llama PRIVATE llama log)

# JNI 边界不抛异常；llama.cpp 侧保持默认行为
target_compile_options(videonotes_llama PRIVATE -fexceptions)

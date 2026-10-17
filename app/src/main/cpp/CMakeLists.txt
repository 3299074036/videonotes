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
    # 2026-10-17: v0.7.0 在天玑 9400 上 prefill 首 batch hang，降级到 2026-09-14 的 v0.4.1 排查
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

# 2026-10-17: prefill 在天玑 9400 上"hang"——方案 C（最小化 native 测试）证实
# 不是死锁而是吞吐病态（~0.4 tok/s，预期 100+ tok/s）。
# 根因：GGML_NATIVE=OFF 且无 -march 时按 baseline armv8-a 编译，
# __ARM_FEATURE_DOTPROD 等宏未定义，量化 matmul 走标量/ plain-NEON fallback。
# A/B 对照：显式指定 armv9-a（DOTPROD + FP16向量 + SVE/SVE2），验证吞吐是否恢复。
# 注意：此 .so 需要 armv8.2+dotprod/fp16/sve 的 CPU（天玑 9400 为 armv9.2，可跑）；
# 若对照证实有效，正式发版时再决定保守档（armv8.2-a+dotprod+fp16）或运行时多版本分发。
# 根因（2026-10-17 实锤）：Gradle debug 构建 → CMAKE_BUILD_TYPE=Debug → 无 -O 标志 → -O0。
# -O0 下标量代码（dequantize/scale 转换/循环控制）完全不优化，实测只有 -O3 的 5-8% 效率。
# 诊断构建也要开 -O3（保留 -g），否则测出来的不是真实性能。
add_compile_options(-O3)

set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
# A/B 对照 2：关闭 repack，走经典 q4_K + vec_dot(sdot) 路径。
# 若关闭后明显变快 → v0.4.1 的 repack GEMM 内核在这颗 SoC 上有性能问题；
# 若一样慢 → 问题在更深层（线程/系统/v0.4.1 ARM 后端整体）。
set(GGML_CPU_REPACK OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_CPP_DIR}" llama.cpp-build)

add_library(videonotes_llama SHARED llama_jni.cpp)

# llama target 的 PUBLIC include 目录（含 llama.h）会自动传递
target_link_libraries(videonotes_llama PRIVATE llama log)

# JNI 边界不抛异常；llama.cpp 侧保持默认行为
target_compile_options(videonotes_llama PRIVATE -fexceptions)

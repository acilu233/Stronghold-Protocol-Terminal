# ---------------------------------------------------------------------------
# nodebridge —— 把预编译的 libnode.so 接到 Java 上的一层薄胶水
#
# 只有这一个源文件。libnode.so 本身是 nodejs-mobile 在 CI 上编好的
# （NDK 交叉编译 Node 22 要几个小时），我们只负责加载并调用它。
# ---------------------------------------------------------------------------
cmake_minimum_required(VERSION 3.22.1)

project(nodebridge CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- 预编译的 Node 运行时 -----------------------------------------------------
# 两个 ABI 的 libnode.so 都放在 app/src/main/jniLibs/<abi>/，
# Gradle 会把它们直接打进 APK；这里只是让链接器找得到。
add_library(node SHARED IMPORTED)
set_target_properties(node PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnode.so")

# --- 我们的 JNI 胶水 ---------------------------------------------------------
add_library(nodebridge SHARED node_bridge.cpp)

target_include_directories(nodebridge PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/include/node")

find_library(log-lib log)

# 必须链接 libnode：node::Start 是 C++ 符号，直接从 libnode.so 解析
target_link_libraries(nodebridge
    node
    ${log-lib})

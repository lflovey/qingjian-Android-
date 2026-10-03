cmake_minimum_required(VERSION 3.22.1)
project(qingjian_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 阶段1 预置引擎库（纯 C ABI，arm64-v8a）
add_library(qingjian SHARED IMPORTED)
set_target_properties(qingjian PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libqingjian.so
    # 该 .so 没有 SONAME：若按全路径链接，DT_NEEDED 会记录构建机绝对路径，
    # 真机上动态链接器按路径找库必失败。IMPORTED_NO_SONAME 让 CMake 改用
    # -L<dir> -lqingjian 形式，DT_NEEDED 只记 "libqingjian.so"。（已用 readelf 实证）
    IMPORTED_NO_SONAME TRUE)

add_library(qingjianjni SHARED qingjian_jni.cpp)

find_library(log-lib log)

target_link_libraries(qingjianjni qingjian ${log-lib} android)

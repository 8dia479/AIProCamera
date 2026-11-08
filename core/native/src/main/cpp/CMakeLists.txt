cmake_minimum_required(VERSION 3.22.1)
project(aipro_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(aipro_native SHARED
        aipro_core.cpp
        image_ops.cpp
        multiframe.cpp
        video_ops.cpp
        jni_bridge.cpp
        jni_image.cpp
        jni_multiframe.cpp
        jni_video.cpp)

target_compile_options(aipro_native PRIVATE -Wall -Wextra -O3)
find_library(log-lib log)
target_link_libraries(aipro_native ${log-lib})

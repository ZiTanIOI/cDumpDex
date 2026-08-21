cmake_minimum_required(VERSION 3.22.1)

project("cdumpdex")

# 设置 Dobby 库路径
set(DOBBY_DIR ${CMAKE_SOURCE_DIR}/dobby)

# 根据架构选择对应的库
if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64")
    set(DOBBY_LIB ${DOBBY_DIR}/libdobby64.a)
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "arm")
    set(DOBBY_LIB ${DOBBY_DIR}/libdobby32.a)
else()
    message(FATAL_ERROR "Unsupported architecture: ${CMAKE_SYSTEM_PROCESSOR}")
endif()

add_library(${CMAKE_PROJECT_NAME} SHARED
    cdumpdex.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${DOBBY_DIR})

target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    log
    ${DOBBY_LIB})

# rootdump - Standalone executable for root-based DEX memory dump
add_executable(rootdump
    rootdump.cpp)

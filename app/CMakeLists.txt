cmake_minimum_required(VERSION 3.6)

project(MaterialFiles C)

add_library(hiddenapi SHARED
        src/main/jni/hiddenapi.c)
target_compile_options(hiddenapi
        PRIVATE
        -Wall
        -Werror)

find_library(LOG_LIBRARY log)
add_library(syscall SHARED
        src/main/jni/syscall.c)
target_compile_options(syscall
        PRIVATE
        -Wall
        -Werror)
target_link_libraries(syscall
        PRIVATE
        ${LOG_LIBRARY})

# libnfs is C-only. Keep it static and out of the shared STL. Multithreading is
# off because the NDK has no libpthread (the cmake probe links -lpthread and fails).
# NFSv4.2 and TLS stay off; the provider forces NFSv3 before mount.
set(ENABLE_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ENABLE_UTILS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(ENABLE_DOCUMENTATION OFF CACHE BOOL "" FORCE)
set(ENABLE_MULTITHREADING OFF CACHE BOOL "" FORCE)
set(ENABLE_NFS4_2 OFF CACHE BOOL "" FORCE)
set(ENABLE_TLS OFF CACHE STRING "" FORCE)
set(_materialfiles_build_shared_libs "${BUILD_SHARED_LIBS}")
set(BUILD_SHARED_LIBS OFF)
add_subdirectory(src/main/jni/libnfs ${CMAKE_CURRENT_BINARY_DIR}/libnfs EXCLUDE_FROM_ALL)
set(BUILD_SHARED_LIBS "${_materialfiles_build_shared_libs}")

add_library(nfsjni SHARED
        src/main/jni/nfs-jni.c)
target_include_directories(nfsjni
        PRIVATE
        src/main/jni/libnfs/include)
target_compile_options(nfsjni
        PRIVATE
        -Wall
        -Werror)
target_link_libraries(nfsjni
        PRIVATE
        nfs
        ${LOG_LIBRARY})

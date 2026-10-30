# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.18)
project(trime_startup LANGUAGES CXX)

# Keep the existing Rime engine intact. This small bridge uses its public C API
# to initialize without the old JNI startup's unconditional maintenance call.
add_library(trime_startup SHARED startup.cc)
target_include_directories(trime_startup PRIVATE vendor)
target_compile_features(trime_startup PRIVATE cxx_std_17)
target_compile_options(trime_startup PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_options(trime_startup PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(trime_startup PRIVATE dl log)

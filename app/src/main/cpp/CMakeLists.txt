cmake_minimum_required(VERSION 3.22.1)
project(cpu_affinity C)

add_library(cpu_affinity SHARED cpu_affinity.c)

# Android 15/16 16 KB ELF page-size alignment compliance
target_link_libraries(cpu_affinity PRIVATE dl)
target_link_options(cpu_affinity PRIVATE "-Wl,-z,max-page-size=16384")
target_compile_options(cpu_affinity PRIVATE -O2 -Wall -Wextra)

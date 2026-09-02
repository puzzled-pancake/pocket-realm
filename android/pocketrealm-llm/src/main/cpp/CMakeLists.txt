cmake_minimum_required(VERSION 3.22)
project(llmexec C)
add_library(llmexec SHARED llmexec.c)
target_link_libraries(llmexec log)

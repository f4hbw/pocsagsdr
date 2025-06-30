cmake_minimum_required(VERSION 3.10)
project(pocsagsdr-native)

add_library(
        pocsagsdr-native
        SHARED
        native-lib.cpp
)

find_library(log-lib log)

target_link_libraries(
        pocsagsdr-native
        ${log-lib}
)

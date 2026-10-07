cmake_minimum_required(VERSION 3.16)
project(oracles_one C)

set(CMAKE_C_STANDARD 11)
if(ANDROID)
  enable_language(CXX)   # SDL's Android controller support (hidapi) has C++ sources
endif()
set(CMAKE_C_STANDARD_REQUIRED ON)

# SDL3: an installed copy when there is one, otherwise built from source (Android always does this).
option(ORACLES_VENDORED_SDL "Build SDL3 from source instead of using an installed one" OFF)
if(NOT ORACLES_VENDORED_SDL AND NOT ANDROID)
  find_package(SDL3 CONFIG QUIET)
endif()
if(NOT SDL3_FOUND)
  include(FetchContent)
  set(SDL_SHARED OFF CACHE BOOL "" FORCE)
  set(SDL_STATIC ON CACHE BOOL "" FORCE)
  set(SDL_TEST_LIBRARY OFF CACHE BOOL "" FORCE)
  if(ANDROID)
    set(SDL_SHARED ON CACHE BOOL "" FORCE)    # the Java side loads libSDL3.so
    set(SDL_STATIC OFF CACHE BOOL "" FORCE)
  endif()
  FetchContent_Declare(SDL3
    GIT_REPOSITORY https://github.com/libsdl-org/SDL.git
    GIT_TAG release-3.2.8
    GIT_SHALLOW TRUE)
  FetchContent_MakeAvailable(SDL3)
endif()

set(SOURCES src/main.c src/gfx.c src/world.c src/link.c src/game.c src/input.c src/hud.c src/items.c src/secrets.c)

if(ANDROID)
  add_library(main SHARED ${SOURCES})      # SDLActivity loads libmain.so
  target_link_libraries(main PRIVATE SDL3::SDL3)
else()
  add_executable(oracles-one ${SOURCES})
  target_link_libraries(oracles-one PRIVATE SDL3::SDL3)
  if(NOT MSVC)
    target_compile_options(oracles-one PRIVATE -Wall -Wextra -Wno-unused-parameter)
    target_link_libraries(oracles-one PRIVATE m)
  endif()
  # the game data sits in assets/ next to the program
  add_custom_target(copy_assets ALL
    COMMAND ${CMAKE_COMMAND} -E copy_directory ${CMAKE_SOURCE_DIR}/assets ${CMAKE_BINARY_DIR}/assets)
endif()

if(NOT ANDROID)
  enable_testing()
  add_executable(test_game tests/test_game.c src/game.c src/secrets.c)
  target_link_libraries(test_game PRIVATE SDL3::SDL3)
  add_test(NAME game COMMAND test_game)
endif()

if(NOT ANDROID)
  add_executable(test_input tests/test_input.c src/input.c src/gfx.c)
  target_link_libraries(test_input PRIVATE SDL3::SDL3)
  add_test(NAME input COMMAND test_input)
endif()

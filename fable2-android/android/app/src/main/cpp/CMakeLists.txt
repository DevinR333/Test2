# Fable 2 - native Android build.
#
# Builds, for arm64-v8a:
#   libSDL3.so          SDL (window, audio, controllers) - loaded by SDLActivity
#   librexruntime.so    ReXGlue runtime (kernel, memory, filesystem, UI)
#   librexgpu-xenos.so  Xbox 360 GPU emulation (Vulkan backend)
#   libmain.so          the recompiled game + the Fable 2 app layer; SDL calls
#                       its SDL_main on the activity's native thread
#
# Inputs (passed by app/build.gradle):
#   FABLE2_DIR  Fable-2-Recomp checkout, AFTER `rexglue codegen` has written
#               generated/default/ from your default.xex (build_apk.ps1 does it)
#   REXSDK_DIR  rexglue-sdk checkout with patches/rexglue-sdk-android.patch

cmake_minimum_required(VERSION 3.25)
project(fable_2_android LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT FABLE2_DIR OR NOT IS_DIRECTORY "${FABLE2_DIR}")
    message(FATAL_ERROR "FABLE2_DIR is not set or missing (got '${FABLE2_DIR}'). Build with build_apk.ps1.")
endif()
if(NOT REXSDK_DIR OR NOT EXISTS "${REXSDK_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "REXSDK_DIR is not set or missing (got '${REXSDK_DIR}'). Build with build_apk.ps1.")
endif()

set(FABLE2_GENERATED_DIR "${FABLE2_DIR}/generated/default")
if(NOT EXISTS "${FABLE2_GENERATED_DIR}/sources.cmake")
    message(FATAL_ERROR
        "${FABLE2_GENERATED_DIR}/sources.cmake not found: run the code generator "
        "on your default.xex first (build_apk.ps1 does this).")
endif()

# Android-side sources shipped with this project (touch controls, JNI).
get_filename_component(FABLE2_ANDROID_SRC "${CMAKE_CURRENT_LIST_DIR}/../../../../../app-src" ABSOLUTE)

# --- ReXGlue SDK (built from source for android-arm64) --------------------
set(REXGLUE_ENABLE_TRACY OFF CACHE BOOL "" FORCE)
set(REXGLUE_USE_VULKAN ON CACHE BOOL "" FORCE)
add_subdirectory("${REXSDK_DIR}" rexglue-sdk)

# --- Recompiled guest code ------------------------------------------------
include("${FABLE2_GENERATED_DIR}/sources.cmake")   # -> GENERATED_SOURCES

set(FABLE2_HEADER_DIRS
    "${FABLE2_DIR}/src/core"
    "${FABLE2_DIR}/src/diagnostics"
    "${FABLE2_DIR}/src/input"
    "${FABLE2_ANDROID_SRC}/input")

add_library(fable_2_recomp OBJECT ${GENERATED_SOURCES})
target_include_directories(fable_2_recomp PRIVATE
    "${FABLE2_DIR}"
    "${FABLE2_DIR}/src"
    "${FABLE2_GENERATED_DIR}"
    ${FABLE2_HEADER_DIRS})
target_link_libraries(fable_2_recomp PRIVATE rex::runtime)
rexglue_apply_target_settings(fable_2_recomp)
# Same PCH order as the desktop build: the generated pch, then the function
# trace hook that redefines REX_FUNC_PROLOGUE on top of it.
target_precompile_headers(fable_2_recomp PRIVATE
    "${FABLE2_GENERATED_DIR}/fable_2_pch.h"
    "${FABLE2_DIR}/src/core/fable2_func_trace.h")
# Keep the (very large) generated code's debug info minimal.
target_compile_options(fable_2_recomp PRIVATE -g0)

# --- Game library (libmain.so) --------------------------------------------
file(GLOB_RECURSE FABLE2_HOTFUNC_SOURCES CONFIGURE_DEPENDS "${FABLE2_DIR}/src/core/hotfunc/*.cpp")

add_library(main SHARED
    "${FABLE2_DIR}/src/main.cpp"
    "${FABLE2_DIR}/src/core/fable2_config.cpp"
    "${FABLE2_DIR}/src/core/fable2_patches.cpp"
    "${FABLE2_DIR}/src/core/fable2_hooks.cpp"
    ${FABLE2_HOTFUNC_SOURCES}
    "${FABLE2_ANDROID_SRC}/android_jni.cpp")

target_include_directories(main PRIVATE
    "${FABLE2_DIR}"
    "${FABLE2_DIR}/src"
    "${FABLE2_GENERATED_DIR}"
    ${FABLE2_HEADER_DIRS})
target_include_directories(main SYSTEM PRIVATE
    "${FABLE2_DIR}/thirdparty/tomlplusplus/include"
    # rex/rex_app.h includes imgui.h; the installed SDK ships it in include/,
    # a source build keeps it on the (object) imgui target only.
    "$<TARGET_PROPERTY:imgui,INTERFACE_INCLUDE_DIRECTORIES>")
# The desktop diagnostics (opt-in debug probes) use a few Win32 calls; give
# them inert stand-ins instead of editing every probe.
target_compile_options(main PRIVATE
    "SHELL:-include ${FABLE2_ANDROID_SRC}/compat/win32_compat.h"
    -Wno-keyword-macro)

target_link_libraries(main PRIVATE fable_2_recomp rex::runtime android log)

# SDL entry point + ReXApp, stage the GPU plugin, arm64 flags.
rexglue_configure_target(main GPU_PLUGINS xenos)

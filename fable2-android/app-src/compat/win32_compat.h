// fable_2 (Android) - stand-ins for the few Win32 APIs used by the desktop
// build's diagnostic probes (src/diagnostics/*).
//
// Force-included into the game library on non-Windows builds. Every probe
// that needs these is opt-in through an environment variable (FABLE2_*), so
// on a phone they never run; this only has to make them compile and fail
// safe if someone does switch one on:
//   - VirtualQuery reports nothing (region scans find no memory),
//   - VirtualAlloc fails (probes that need a scratch page skip their work),
//   - __try/__except run the body unprotected (no SEH on Linux/Android).

#pragma once

#ifndef _WIN32

#include <cstddef>
#include <cstdint>
#include <thread>

#include <unistd.h>

using DWORD = std::uint32_t;
using BOOL = int;
using LPVOID = void*;
using SIZE_T = std::size_t;

#ifndef WINAPI
#define WINAPI
#endif

#define MEM_COMMIT 0x00001000u
#define MEM_RESERVE 0x00002000u
#define MEM_FREE 0x00010000u
#define PAGE_NOACCESS 0x01u
#define PAGE_READONLY 0x02u
#define PAGE_READWRITE 0x04u
#define PAGE_WRITECOPY 0x08u
#define PAGE_GUARD 0x100u
#define EXCEPTION_EXECUTE_HANDLER 1

#define __declspec(x) __attribute__((x))
// Same spelling as libstdc++'s own __try (bits/exception_defines.h), so the
// two definitions agree whichever C++ library is in use (the NDK's libc++
// has none). A C++ catch does not see hardware faults: the body is simply
// unprotected, as documented above.
#ifndef __try
#define __try try
#endif
#define __except(filter) catch (...)

struct MEMORY_BASIC_INFORMATION {
  void* BaseAddress;
  void* AllocationBase;
  DWORD AllocationProtect;
  SIZE_T RegionSize;
  DWORD State;
  DWORD Protect;
  DWORD Type;
};

inline SIZE_T VirtualQuery(const void*, MEMORY_BASIC_INFORMATION*, SIZE_T) {
  return 0;
}

inline void* VirtualAlloc(void*, SIZE_T, DWORD, DWORD) {
  return nullptr;
}

inline void Sleep(DWORD milliseconds) {
  usleep(static_cast<useconds_t>(milliseconds) * 1000u);
}

// Fire-and-forget worker threads only (no handle is ever waited on).
using LPTHREAD_START_ROUTINE = DWORD (*)(LPVOID);
inline void* CreateThread(void*, SIZE_T, LPTHREAD_START_ROUTINE start, LPVOID arg, DWORD,
                          DWORD*) {
  std::thread([start, arg] { start(arg); }).detach();
  return reinterpret_cast<void*>(1);
}

#endif  // !_WIN32

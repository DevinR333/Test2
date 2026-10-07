// "Build APK.exe": runs build_apk.bat from the folder the .exe is in, in a console window, so the
// whole Android build is one double-click. Built with MinGW: tools/make_zip.sh.
#include <windows.h>
#include <stdio.h>
#include <wchar.h>

int wmain(void) {
  wchar_t dir[MAX_PATH], cmd[MAX_PATH * 2];
  DWORD n = GetModuleFileNameW(NULL, dir, MAX_PATH);
  if (n == 0 || n >= MAX_PATH) { fwprintf(stderr, L"Can't find this program's folder.\n"); return 1; }
  wchar_t *slash = wcsrchr(dir, L'\\');
  if (slash) *slash = 0;
  SetCurrentDirectoryW(dir);
  if (GetFileAttributesW(L"build_apk.bat") == INVALID_FILE_ATTRIBUTES) {
    fwprintf(stderr, L"build_apk.bat is missing next to this program. Unzip the whole folder and try again.\n");
    system("pause");
    return 1;
  }
  SetConsoleTitleW(L"Oracles One - building the APK");
  _snwprintf(cmd, sizeof cmd / sizeof cmd[0], L"cmd /c \"\"%ls\\build_apk.bat\"\"", dir);
  STARTUPINFOW si;
  ZeroMemory(&si, sizeof si);
  si.cb = sizeof si;
  PROCESS_INFORMATION pi;
  if (!CreateProcessW(NULL, cmd, NULL, NULL, FALSE, 0, NULL, dir, &si, &pi)) {
    fwprintf(stderr, L"Couldn't start build_apk.bat (error %lu).\n", GetLastError());
    system("pause");
    return 1;
  }
  WaitForSingleObject(pi.hProcess, INFINITE);
  DWORD code = 1;
  GetExitCodeProcess(pi.hProcess, &code);
  CloseHandle(pi.hThread);
  CloseHandle(pi.hProcess);
  return (int)code;
}

@echo off
rem Builds the Windows engine's glue on the VM (winvm.sh build): glue_test.exe (the pure logic's tests, run first),
rem webview_windows.dll (the library's) and webview_windows_testing.dll (the sample's test hooks).
rem   build.cmd [x64|arm64]
rem /MT: the static CRT, so the JBR's msvcp140.dll is never the one this DLL runs on. The WebView2 loader links
rem statically (WebView2LoaderStatic.lib): one file ships. jawt is found at run time, so no JDK library is linked.
setlocal enabledelayedexpansion
set "ARCH=%~1"
if "%ARCH%"=="" set "ARCH=x64"
set "SRC=%~dp0"
set "OUT=%~dp0out\%ARCH%"
set "OBJ=%~dp0obj\%ARCH%"
set "WV2=C:\wv2test\sdk\webview2\build\native"
set "JDK=C:\wv2test\jbr"
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
set "VS="
for /f "usebackq tokens=*" %%i in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VS=%%i"
if not defined VS (echo wvbuild: no MSVC; winvm.sh setup says what is missing & exit /b 2)
set "VCARCH=x64"
if "%ARCH%"=="arm64" set "VCARCH=x64_arm64"
call "%VS%\VC\Auxiliary\Build\vcvarsall.bat" %VCARCH% >nul
if errorlevel 1 (echo wvbuild: vcvarsall %VCARCH% failed & exit /b 2)
rem Each output's objects in a folder of their own, emptied first: a link never takes another output's or a stale object.
for %%d in ("%OUT%" "%OBJ%\test" "%OBJ%\dll" "%OBJ%\testing") do if not exist "%%~d" mkdir "%%~d"
del /q "%OBJ%\test\*.obj" "%OBJ%\dll\*.obj" "%OBJ%\testing\*.obj" 2>nul
set "CFLAGS=/nologo /c /std:c++20 /EHsc /permissive- /Zc:__cplusplus /utf-8 /bigobj /O2 /MT /W3 /DUNICODE /D_UNICODE /DNOMINMAX /I%SRC% /I%WV2%\include /I%JDK%\include /I%JDK%\include\win32"

rem 1. The pure logic's tests: a failing test stops the build. Run here on x64 only.
if "%ARCH%"=="x64" (
  cl %CFLAGS% /Fo%OBJ%\test\glue_test.obj %SRC%test\glue_test.cpp || (echo wvbuild: glue_test compile FAILED & exit /b 1)
  link /nologo %OBJ%\test\glue_test.obj /OUT:%OUT%\glue_test.exe || (echo wvbuild: glue_test link FAILED & exit /b 1)
  %OUT%\glue_test.exe || (echo wvbuild: glue_test FAILED & exit /b 1)
)
if not exist "%SRC%jni.cpp" (echo wvbuild: tests OK; no jni.cpp yet, nothing else to build & exit /b 0)

rem 2. The library's DLL: every source here, compiled in parallel (/MP; C++/WinRT's headers make each one slow).
cl %CFLAGS% /MP /Fo%OBJ%\dll\ %SRC%*.cpp || (echo wvbuild: compile FAILED & exit /b 1)
link /nologo /DLL /OUT:%OUT%\webview_windows.dll %OBJ%\dll\*.obj %WV2%\%ARCH%\WebView2LoaderStatic.lib ^
  windowsapp.lib d3d11.lib dxgi.lib CoreMessaging.lib user32.lib gdi32.lib ole32.lib oleaut32.lib advapi32.lib ^
  shell32.lib shlwapi.lib version.lib || (echo wvbuild: link FAILED & exit /b 1)

rem 3. The sample's test hooks: never in the library's jar. (They arrive in Task 27.)
if exist "%SRC%testing\testing_jni.cpp" (
  cl %CFLAGS% /MP /Fo%OBJ%\testing\ %SRC%testing\*.cpp || (echo wvbuild: testing compile FAILED & exit /b 1)
  link /nologo /DLL /OUT:%OUT%\webview_windows_testing.dll %OBJ%\testing\*.obj user32.lib gdi32.lib imm32.lib || (echo wvbuild: testing link FAILED & exit /b 1)
)

rem 4. What the DLL exports and needs: JNI_OnLoad and the natives; no WebView2Loader.dll, no dynamic CRT.
dumpbin /nologo /exports %OUT%\webview_windows.dll | findstr /c:"JNI_OnLoad" >nul || (echo wvbuild: no JNI_OnLoad export & exit /b 1)
dumpbin /nologo /dependents %OUT%\webview_windows.dll | findstr /i "WebView2Loader.dll msvcp140.dll vcruntime140.dll jawt.dll" && (echo wvbuild: a dependency that must not be there & exit /b 1)
for /f %%n in ('dumpbin /nologo /exports %OUT%\webview_windows.dll ^| findstr /c:"Java_dev_supermux_webview_engine_windows_WinGlue_" ^| find /c /v ""') do echo wvbuild: OK %OUT%\webview_windows.dll, %%n natives
rem findstr's "not found" above leaves errorlevel 1: the build itself succeeded.
exit /b 0

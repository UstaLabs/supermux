@echo off
rem Builds wv2capture.dll (x64) for the WebView2 capture test, on the Windows VM. Needs MSVC x64 and the Windows 11
rem SDK (vm/setup.ps1), the WebView2 SDK in C:\wv2test\sdk\webview2 and the JBR in C:\wv2test\jbr (JNI, JAWT, jawt.lib).
rem   build.cmd                      compile every .cpp here; link the DLL once jni_bridge.cpp exists
rem   build.cmd /DWV2_NO_DIRTY_API   the same, for a Windows SDK without GraphicsCaptureDirtyRegionMode
rem /MT: the static CRT, so the JBR's older msvcp140.dll can never be the one this DLL runs on.
setlocal
set "SRC=%~dp0"
set "OUT=%~dp0out"
set "WV2=C:\wv2test\sdk\webview2\build\native"
set "JDK=C:\wv2test\jbr"
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
set "VS="
for /f "usebackq tokens=*" %%i in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VS=%%i"
if not defined VS (echo wv2build: no MSVC x64 tools; run vm/setup.ps1 & exit /b 2)
call "%VS%\VC\Auxiliary\Build\vcvarsall.bat" x64 >nul
if errorlevel 1 (echo wv2build: vcvarsall failed & exit /b 2)
if not exist "%OUT%" mkdir "%OUT%"
del /q "%OUT%\*.obj" 2>nul
cl /nologo /c /MP /std:c++20 /EHsc /permissive- /Zc:__cplusplus /utf-8 /bigobj /O2 /MT /W3 ^
   /DUNICODE /D_UNICODE /DNOMINMAX %1 ^
   /I%WV2%\include /I%JDK%\include /I%JDK%\include\win32 ^
   /Fo%OUT%\ %SRC%*.cpp
if errorlevel 1 (echo wv2build: compile FAILED & exit /b 1)
if not exist "%OUT%\jni_bridge.obj" (echo wv2build: compiled OK; no jni_bridge.cpp yet, so nothing to link & exit /b 0)
cl /nologo /LD /MT %OUT%\*.obj /Fe%OUT%\wv2capture.dll /link /NOLOGO %JDK%\lib\jawt.lib ^
   windowsapp.lib d3d11.lib dxgi.lib CoreMessaging.lib user32.lib gdi32.lib ole32.lib advapi32.lib
if errorlevel 1 (echo wv2build: link FAILED & exit /b 1)
copy /y %WV2%\x64\WebView2Loader.dll %OUT%\ >nul
echo wv2build: OK %OUT%\wv2capture.dll

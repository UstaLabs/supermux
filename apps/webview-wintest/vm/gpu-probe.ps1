# Builds vm/gpu-probe.cpp with the VS Build Tools (once, or when the source changes) and runs it. Run it with
# `vm.sh ui` so it sees tester's desktop: in the SSH session (session 0) DXGI lists no outputs.
#   vm.sh push apps/webview-wintest/vm/gpu-probe.cpp C:/wv2test/vm/gpu-probe.cpp; vm.sh ui apps/webview-wintest/vm/gpu-probe.ps1
$ErrorActionPreference = 'Stop'
$dir = 'C:\wv2test\vm'
$source = "$dir\gpu-probe.cpp"
$exe = "$dir\gpu-probe.exe"
if (-not (Test-Path $exe) -or (Get-Item $source).LastWriteTime -gt (Get-Item $exe).LastWriteTime) {
    $vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
    $vs = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    $build = "call `"$vs\VC\Auxiliary\Build\vcvarsall.bat`" x64 >nul && cl /nologo /EHsc /O2 /DUNICODE /D_UNICODE " +
        "/Fo$dir\ /Fe$exe $source"
    cmd /c $build
    if ($LASTEXITCODE -ne 0) { throw "gpu-probe: build failed ($LASTEXITCODE)" }
}
& $exe

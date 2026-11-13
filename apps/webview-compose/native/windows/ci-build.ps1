# Builds the Windows engine's glue on a Windows machine with MSVC (a GitHub runner; the release's Windows job, #137), and
# puts webview_windows.dll where :webview-compose:stageWindowsGlue packages it: apps/webview-compose/build/native/
# windows-<arch>/. On the dev VM, `winvm.sh build` does the same through the same build.cmd.
#   pwsh ci-build.ps1 [-Arch x64|arm64]
# Needs JAVA_HOME (a JDK: jni.h) and Visual Studio's C++ tools (build.cmd finds them with vswhere). The WebView2 SDK
# (its header and static loader) comes from its NuGet package, pinned by version and sha256: the version the VM's
# setup.ps1 installs.
param([ValidateSet('x64', 'arm64')][string]$Arch = 'x64')
$ErrorActionPreference = 'Stop'

$sdkVersion = '1.0.4258.31'
$sdkSha256 = '56f7f4b8bf9aee4b8efefbbdd4f67d5f74ebd1b100ed0806da71bf76af481aa9'

$here = $PSScriptRoot
$module = (Resolve-Path (Join-Path $here '..\..')).Path
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'include\jni.h'))) { throw "ci-build: JAVA_HOME has no include\jni.h (a JDK is needed)" }

$work = Join-Path ([IO.Path]::GetTempPath()) "webview2-sdk-$sdkVersion"
$package = Join-Path $work 'webview2.zip'
$sdk = Join-Path $work 'sdk'
New-Item -ItemType Directory -Force $work | Out-Null
if (-not (Test-Path $package) -or (Get-FileHash $package -Algorithm SHA256).Hash.ToLower() -ne $sdkSha256) {
    Invoke-WebRequest -Uri "https://www.nuget.org/api/v2/package/Microsoft.Web.WebView2/$sdkVersion" -OutFile $package -UseBasicParsing
}
$sha = (Get-FileHash $package -Algorithm SHA256).Hash.ToLower()
if ($sha -ne $sdkSha256) { throw "ci-build: the WebView2 SDK $sdkVersion package hashes to $sha, not the pinned $sdkSha256" }
if (Test-Path $sdk) { Remove-Item -Recurse -Force $sdk }
Expand-Archive -Path $package -DestinationPath $sdk
$native = Join-Path $sdk 'build\native'
foreach ($need in @('include\WebView2.h', "$Arch\WebView2LoaderStatic.lib")) {
    if (-not (Test-Path (Join-Path $native $need))) { throw "ci-build: the WebView2 SDK package has no build\native\$need" }
}

# build.cmd runs the glue's logic tests first (for the host's own arch) and checks what the DLL exports and links.
$env:WV2 = $native
$env:JDK = $env:JAVA_HOME
& cmd /c call "$here\build.cmd" $Arch
if ($LASTEXITCODE -ne 0) { throw "ci-build: build.cmd $Arch failed ($LASTEXITCODE)" }

$dll = Join-Path $here "out\$Arch\webview_windows.dll"
if (-not (Test-Path $dll)) { throw "ci-build: build.cmd made no $dll" }
$dest = Join-Path $module "build\native\windows-$Arch"
New-Item -ItemType Directory -Force $dest | Out-Null
Copy-Item $dll $dest -Force
$built = Get-Item (Join-Path $dest 'webview_windows.dll')
Write-Host "ci-build: $($built.FullName) $($built.Length) B sha256 $((Get-FileHash $built.FullName -Algorithm SHA256).Hash.ToLower())"

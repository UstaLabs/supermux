# One-time, idempotent setup of the Windows 11 x64 VM (supermux-windows on desktop) for the WebView2 capture test
# (apps/webview-wintest). Every step checks before it acts, and the Build Tools install runs as a SYSTEM scheduled task,
# so neither an SSH disconnect nor this script ending stops it. Re-run it to resume.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = 'C:\wv2test'
foreach ($dir in 'dl', 'lib', 'native\out', 'results', 'sdk', 'udf', 'ui', 'vm') {
    New-Item -ItemType Directory -Force (Join-Path $root $dir) | Out-Null
}

# 1. The WebView2 SDK (headers and the x64 loader), pinned and checksummed. Its APIs are checked against the installed
#    runtime at run time: the test only uses interfaces from 2020-2021 (composition controller, pointer input, cursor).
$wv2Version = '1.0.4258.31'
$wv2Sha256 = '56F7F4B8BF9AEE4B8EFEFBBDD4F67D5F74EBD1B100ED0806DA71BF76AF481AA9'
$wv2Dir = Join-Path $root 'sdk\webview2'
if (-not (Test-Path "$wv2Dir\build\native\include\WebView2.h")) {
    $pkg = Join-Path $root "dl\microsoft.web.webview2.$wv2Version.nupkg"
    if (-not (Test-Path $pkg)) {
        & curl.exe -sSfL -o $pkg "https://www.nuget.org/api/v2/package/Microsoft.Web.WebView2/$wv2Version"
        if ($LASTEXITCODE -ne 0) { throw "WebView2 SDK download failed: curl $LASTEXITCODE" }
    }
    $hash = (Get-FileHash $pkg -Algorithm SHA256).Hash
    if ($hash -ne $wv2Sha256) { Remove-Item $pkg; throw "WebView2 SDK checksum mismatch: $hash" }
    New-Item -ItemType Directory -Force $wv2Dir | Out-Null
    & tar.exe -xf $pkg -C $wv2Dir
    if ($LASTEXITCODE -ne 0) { throw "WebView2 SDK unpack failed: tar $LASTEXITCODE" }
}
"setup: WebView2 SDK $wv2Version in $wv2Dir (x64 loader: $(Test-Path "$wv2Dir\build\native\x64\WebView2Loader.dll"))"

# 2. The JVM the app runs on, and the JNI/JAWT headers and jawt.lib the DLL builds against: JetBrains Runtime 21.0.11
#    for x64, the SDK flavour (the plain runtime has no include\ or lib\jawt.lib). Checksum from cache-redirector.
$jbrName = 'jbrsdk-21.0.11-windows-x64-b1163.125'
$jbrSha512 = '0442A85FB203A70879701396DF5F1BB347332AC680C511F078DE17D4861C91FE8075115F6A9D3325F07E083F36EB6A9F532A07CAB541A8649703D900DFC04022'
$jbrDir = Join-Path $root 'jbr'
if (-not ((Test-Path "$jbrDir\bin\javaw.exe") -and (Test-Path "$jbrDir\include\jawt.h") -and (Test-Path "$jbrDir\lib\jawt.lib"))) {
    $archive = Join-Path $root "dl\$jbrName.tar.gz"
    if (-not (Test-Path $archive) -or (Get-FileHash $archive -Algorithm SHA512).Hash -ne $jbrSha512) {
        & curl.exe -sSfL -o $archive "https://cache-redirector.jetbrains.com/intellij-jbr/$jbrName.tar.gz"
        if ($LASTEXITCODE -ne 0) { throw "JBR download failed: curl $LASTEXITCODE" }
    }
    $hash = (Get-FileHash $archive -Algorithm SHA512).Hash
    if ($hash -ne $jbrSha512) { Remove-Item $archive; throw "JBR checksum mismatch: $hash" }
    $unpack = Join-Path $root 'dl\jbr-unpack'
    Remove-Item -Recurse -Force $unpack, $jbrDir -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $unpack | Out-Null
    & tar.exe -xf $archive -C $unpack
    if ($LASTEXITCODE -ne 0) { throw "JBR unpack failed: tar $LASTEXITCODE" }
    $top = @(Get-ChildItem $unpack -Directory)
    if ($top.Count -ne 1) { throw "JBR archive has $($top.Count) top-level folders" }
    Move-Item $top[0].FullName $jbrDir
    Remove-Item -Recurse -Force $unpack
}
"setup: JBR in $jbrDir ($((Get-Content "$jbrDir\release" | Select-String 'JAVA_RUNTIME_VERSION|^IMPLEMENTOR=') -join ' '))"

# The Evergreen WebView2 runtime the test runs on (machine-wide install).
$runtimeKey = 'HKLM:\SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}'
"setup: WebView2 runtime $((Get-ItemProperty $runtimeKey -ErrorAction SilentlyContinue).pv)"

# 3. MSVC for x64 and the Windows 11 SDK 26100 (C++/WinRT headers included), from the VS 2022 Build Tools. Only the
#    components the DLL needs (no --includeRecommended) and no package cache (--nocache): this VM had 18 GB free.
$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$installPath = 'C:\BuildTools'
$components = @(
    'Microsoft.VisualStudio.Workload.VCTools',
    'Microsoft.VisualStudio.Component.VC.Tools.x86.x64',
    'Microsoft.VisualStudio.Component.Windows11SDK.26100'
)
function Find-Msvc {
    if (-not (Test-Path $vswhere)) { return $null }
    & $vswhere -products * -requires $components[1] $components[2] -property installationPath
}
$ready = Find-Msvc
if ($ready) { "setup: MSVC x64 + Windows SDK 26100 ready in $ready"; return }

$task = Get-ScheduledTask -TaskName wv2-vs -ErrorAction SilentlyContinue
if ($task -and $task.State -eq 'Running') { 'setup: the Build Tools install is still running; poll vs-status.ps1'; return }

$free = (Get-Volume -DriveLetter C).SizeRemaining / 1GB
if ($free -lt 10) { throw ("setup: only {0:N1} GB free on C:; the Build Tools need about 7" -f $free) }

$bootstrapper = Join-Path $root 'dl\vs_BuildTools.exe'
if (-not (Test-Path $bootstrapper)) {
    & curl.exe -sSfL -o $bootstrapper 'https://aka.ms/vs/17/release/vs_BuildTools.exe'
    if ($LASTEXITCODE -ne 0) { throw "Build Tools bootstrapper download failed: curl $LASTEXITCODE" }
}
# An instance an earlier run left incomplete is finished with 'repair'; a complete one that lacks a component gets
# 'modify'; otherwise this is a fresh install.
$instance = $null
if (Test-Path $vswhere) {
    $instance = & $vswhere -all -products * -format json | ConvertFrom-Json | Where-Object { $_.installationPath -eq $installPath }
}
$adds = ($components | ForEach-Object { "--add $_" }) -join ' '
if (-not $instance) {
    $verb = 'install'
    $arguments = "--installPath $installPath $adds --quiet --wait --norestart --nocache"
} elseif ($instance.isComplete -eq $false) {
    $verb = 'repair'
    $arguments = "repair --installPath $installPath --quiet --wait --norestart --nocache"
} else {
    $verb = 'modify'
    $arguments = "modify --installPath $installPath $adds --quiet --wait --norestart --nocache"
}
$result = Join-Path $root 'ui\vs-install.txt'
$script = Join-Path $root 'ui\vs-install.cmd'
@"
@echo off
"$bootstrapper" $arguments
echo exit %ERRORLEVEL% > "$result"
"@ | Set-Content -Encoding ASCII $script
Remove-Item $result -ErrorAction SilentlyContinue
$action = New-ScheduledTaskAction -Execute 'cmd.exe' -Argument "/c $script"
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Hours 3) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName wv2-vs -Action $action -Principal $principal -Settings $settings -Force | Out-Null
Start-ScheduledTask -TaskName wv2-vs
"setup: started the Build Tools $verb as task wv2-vs; poll vs-status.ps1"

# Readies supermux-windows (or its GPU copy) for the Windows engine: folders under C:\wvc, and a check of the tools the
# feasibility test installed and kept (W12). Installs nothing; says what is missing. Safe to repeat.
$ErrorActionPreference = 'Stop'
foreach ($dir in 'native\out\x64', 'native\out\arm64', 'native\test', 'native\testing', 'lib', 'results', 'ui', 'vm') {
    New-Item -ItemType Directory -Force (Join-Path 'C:\wvc' $dir) | Out-Null
}
$checks = [ordered]@{
    'JBR 21 (javaw, jawt.h, jawt.lib)' = (Test-Path 'C:\wv2test\jbr\bin\javaw.exe') -and (Test-Path 'C:\wv2test\jbr\include\jawt.h') -and (Test-Path 'C:\wv2test\jbr\lib\jawt.lib')
    'WebView2 SDK 1.0.4258.31 (header, static loaders)' = (Test-Path 'C:\wv2test\sdk\webview2\build\native\include\WebView2.h') -and (Test-Path 'C:\wv2test\sdk\webview2\build\native\x64\WebView2LoaderStatic.lib')
}
$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$msvc = if (Test-Path $vswhere) { & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath } else { $null }
$checks['MSVC x64 (C:\BuildTools)'] = [bool]$msvc
$arm = if (Test-Path $vswhere) { & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.ARM64 -property installationPath } else { $null }
foreach ($name in $checks.Keys) { "setup: $(if ($checks[$name]) { 'ok     ' } else { 'MISSING' }) $name" }
"setup: $(if ($arm) { 'ok     ' } else { 'absent ' }) MSVC ARM64 cross tools (Task 32 adds them)"
$runtime = (Get-ItemProperty 'HKLM:\SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}' -ErrorAction SilentlyContinue).pv
"setup: WebView2 runtime $(if ($runtime) { $runtime } else { 'NOT INSTALLED' })"
"setup: C: free {0:N1} GB" -f ((Get-Volume -DriveLetter C).SizeRemaining / 1GB)
if ($checks.Values -contains $false) { 'setup: something is missing: apps/webview-wintest/vm/setup.ps1 installs it (W12)'; exit 1 }

# Packages the sample from C:\wvc\lib-release as an app image (no installer: WiX isn't needed for one), with the JBR as
# its runtime as it is (no jlink, so no jmods needed). Safe to repeat: the old image is removed first. (winvm.sh ps)
$ErrorActionPreference = 'Stop'
$jpackage = 'C:\wv2test\jbr\bin\jpackage.exe'
if (-not (Test-Path $jpackage)) { 'package: no jpackage in C:\wv2test\jbr: the packaged run is skipped (results: open item)'; exit 3 }
$main = Get-ChildItem C:\wvc\lib-release -Filter 'webview-sample*.jar' | Select-Object -First 1
if (-not $main) { 'package: no webview-sample jar in C:\wvc\lib-release: winvm.sh stage-release first'; exit 1 }
Remove-Item -Recurse -Force C:\wvc\app -ErrorAction SilentlyContinue
# jpackage writes progress to stderr: under 'Stop' that would end the script, so it is judged by its exit code alone.
$ErrorActionPreference = 'Continue'
& $jpackage --type app-image --name webview-sample --dest C:\wvc\app --input C:\wvc\lib-release `
    --main-jar $main.Name --main-class dev.supermux.webview.sample.MainKt --runtime-image C:\wv2test\jbr 2>&1 | ForEach-Object { "jpackage: $_" }
if ($LASTEXITCODE -ne 0) { "package: jpackage exit $LASTEXITCODE"; exit 1 }
"package: C:\wvc\app\webview-sample\webview-sample.exe, {0:N0} MB" -f ((Get-ChildItem -Recurse C:\wvc\app | Measure-Object Length -Sum).Sum / 1MB)

[CmdletBinding()]
param(
    [string]$InstallDir = "",
    [string]$MsiPath = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Resolve-SupermuxInstall {
    if ($InstallDir) {
        return (Resolve-Path -LiteralPath $InstallDir).Path
    }

    $candidates = @(
        (Join-Path $env:ProgramFiles "supermux"),
        (Join-Path ${env:ProgramFiles(x86)} "supermux"),
        (Join-Path $env:LOCALAPPDATA "Programs\supermux")
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Container) }

    if ($candidates.Count -ne 1) {
        throw "Expected exactly one supermux install directory; found $($candidates.Count): $($candidates -join ', ')"
    }
    return (Resolve-Path -LiteralPath $candidates[0]).Path
}

function Get-PeMachine([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    try {
        $reader = [IO.BinaryReader]::new($stream)
        if ($reader.ReadUInt16() -ne 0x5A4D) { throw "$Path is not a PE executable" }
        $stream.Position = 0x3C
        $peOffset = $reader.ReadUInt32()
        $stream.Position = $peOffset
        if ($reader.ReadUInt32() -ne 0x00004550) { throw "$Path has no PE signature" }
        $machine = $reader.ReadUInt16()
        $architecture = switch ($machine) {
            34404 { "x64" }
            43620 { "arm64" }
            332 { "x86" }
            default { "unknown-0x{0:X4}" -f $machine }
        }
        return $architecture
    } finally {
        $stream.Dispose()
    }
}

$root = Resolve-SupermuxInstall
$required = @("supermux-broker.exe", "mux-sessiond.exe", "frpc.exe")
$evidence = foreach ($name in $required) {
    $matches = @(Get-ChildItem -LiteralPath $root -Filter $name -File -Recurse)
    if ($matches.Count -ne 1) {
        throw "Expected exactly one $name below $root; found $($matches.Count)"
    }
    $file = $matches[0]
    if ($file.Length -le 0) { throw "$($file.FullName) is empty" }
    [pscustomobject]@{
        Name = $name
        Path = $file.FullName
        Bytes = $file.Length
        Architecture = Get-PeMachine $file.FullName
        Sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

if ($MsiPath) {
    $resolvedMsi = (Resolve-Path -LiteralPath $MsiPath).Path
    Write-Host "MSI_SHA256=$((Get-FileHash -LiteralPath $resolvedMsi -Algorithm SHA256).Hash.ToLowerInvariant())"
}
Write-Host "INSTALL_DIR=$root"
$evidence | Format-Table -AutoSize
Write-Host "SUPERMUX_MSI_ASSERT_OK"

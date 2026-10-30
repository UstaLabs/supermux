# Gives the passthrough GPU (supermux-windows-gpu) a display of its own, so Windows composes the desktop on it as on a
# real PC, and switches back. The card has no monitor; the VM's only display is QEMU's virtio GPU, a display-only adapter
# that Windows composes for on the Basic Render Driver (WARP), so apps on the NVIDIA GPU would pay cross-adapter copies
# a real PC doesn't. The Virtual Display Driver (VirtualDrivers, IddCx, signed by SignPath Foundation) adds a virtual
# monitor rendered by the GPU its settings name (IddCxAdapterSetRenderAdapter).
#   gpu-display.ps1 install   download the driver (pinned, SHA-256), write its settings, create Root\MttVDD, install it
#   gpu-display.ps1 gpu       virtual display on, virtio display off: the desktop runs on the NVIDIA GPU
#   gpu-display.ps1 virtio    virtio display on, virtual display off: what supermux-windows (same disk, no card) expects
#   gpu-display.ps1 single    detach every display but the primary from the desktop (after `gpu`, Windows' Basic
#                             Display Driver takes the boot framebuffer over and extends the desktop onto it)
#   gpu-display.ps1 status    the display adapters and monitors
# Elevated (the SSH session: vm.sh ps with a one-line job that calls this file), except `single`, which changes
# tester's desktop and runs there (vm.sh ui). Every mode is idempotent.
param(
    [Parameter(Mandatory = $true)][ValidateSet('install', 'gpu', 'virtio', 'single', 'status')][string]$Mode,
    [string]$Gpu = 'NVIDIA GeForce GTX 1660 SUPER'
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = 'C:\wv2test\vdd'
$hwid = 'Root\MttVDD'

function Show-Status {
    Get-PnpDevice -Class Display | Format-Table -AutoSize Status, FriendlyName, Problem, InstanceId | Out-String -Width 220
    Get-CimInstance -Namespace root\wmi WmiMonitorBasicDisplayParams -ErrorAction SilentlyContinue |
        ForEach-Object { "monitor $($_.InstanceName) active=$($_.Active)" }
}
function Get-Vdd { Get-PnpDevice -ErrorAction SilentlyContinue | Where-Object { $_.HardwareID -contains $hwid } | Select-Object -First 1 }
function Get-Virtio { Get-PnpDevice -Class Display | Where-Object { $_.InstanceId -like 'PCI\VEN_1AF4&DEV_1050*' } | Select-Object -First 1 }

switch ($Mode) {
    'install' {
        # Release 25.7.23's driver-only zip (driver 24.12.24, x64 despite its name); the digest is GitHub's for the asset.
        $url = 'https://github.com/VirtualDrivers/Virtual-Display-Driver/releases/download/25.7.23/VirtualDisplayDriver-x86.Driver.Only.zip'
        $sha256 = 'E24210692B442B39AF763536330CE78B423F19342B7A7792C26DE3944E418B3A'
        New-Item -ItemType Directory -Force $root | Out-Null
        $zip = "$root\vdd-25.7.23.zip"
        if (-not (Test-Path $zip) -or (Get-FileHash $zip -Algorithm SHA256).Hash -ne $sha256) {
            & curl.exe -sSfL -o $zip $url
            if ($LASTEXITCODE -ne 0) { throw "download failed: curl $LASTEXITCODE" }
            $hash = (Get-FileHash $zip -Algorithm SHA256).Hash
            if ($hash -ne $sha256) { Remove-Item $zip; throw "checksum mismatch: $hash" }
        }
        Expand-Archive -Force $zip $root
        $driver = "$root\VirtualDisplayDriver"
        $catalog = Get-AuthenticodeSignature "$driver\mttvdd.cat"
        if ($catalog.Status -ne 'Valid') { throw "the driver's catalog signature is $($catalog.Status)" }
        "gpu-display: catalog Valid, signed by $($catalog.SignerCertificate.Subject)"

        # One monitor, rendered by $Gpu, 60 Hz only (a common desktop display; DWM, WGC and the page's rAF follow it).
        New-Item -ItemType Directory -Force C:\VirtualDisplayDriver | Out-Null
        @"
<?xml version='1.0' encoding='utf-8'?>
<vdd_settings>
    <monitors><count>1</count></monitors>
    <gpu><friendlyname>$Gpu</friendlyname></gpu>
    <global><g_refresh_rate>60</g_refresh_rate></global>
    <resolutions>
        <resolution><width>1920</width><height>1080</height><refresh_rate>60</refresh_rate></resolution>
        <resolution><width>1280</width><height>800</height><refresh_rate>60</refresh_rate></resolution>
    </resolutions>
    <options>
        <CustomEdid>false</CustomEdid>
        <PreventSpoof>false</PreventSpoof>
        <EdidCeaOverride>false</EdidCeaOverride>
        <HardwareCursor>true</HardwareCursor>
        <SDR10bit>false</SDR10bit>
        <HDRPlus>false</HDRPlus>
        <logging>true</logging>
        <debuglogging>false</debuglogging>
    </options>
</vdd_settings>
"@ | Set-Content -Encoding UTF8 C:\VirtualDisplayDriver\vdd_settings.xml

        # devnode.exe creates the root device (Windows has no tool for that), built here once.
        $exe = 'C:\wv2test\vm\devnode.exe'
        if (-not (Test-Path $exe) -or (Get-Item C:\wv2test\vm\devnode.cpp).LastWriteTime -gt (Get-Item $exe).LastWriteTime) {
            $vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
            $vs = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
            cmd /c "call `"$vs\VC\Auxiliary\Build\vcvarsall.bat`" x64 >nul && cl /nologo /EHsc /O2 /DUNICODE /D_UNICODE /FoC:\wv2test\vm\ /Fe$exe C:\wv2test\vm\devnode.cpp"
            if ($LASTEXITCODE -ne 0) { throw "devnode: build failed ($LASTEXITCODE)" }
        }
        # A package signed by anyone but Microsoft installs without a prompt only if its signer is a trusted publisher:
        # trust it for this install, then take the trust back (the installed driver keeps working).
        $signer = $catalog.SignerCertificate
        $store = [System.Security.Cryptography.X509Certificates.X509Store]::new('TrustedPublisher', 'LocalMachine')
        $store.Open('ReadWrite'); $store.Add($signer); $store.Close()
        try {
            & $exe "$driver\MttVDD.inf" $hwid
            if ($LASTEXITCODE -ne 0) { throw "devnode exit $LASTEXITCODE" }
        } finally {
            $store.Open('ReadWrite'); $store.Remove($signer); $store.Close()
        }
        Start-Sleep -Seconds 5
        Show-Status
    }
    'gpu' {
        $vdd = Get-Vdd
        if (-not $vdd) { throw 'no virtual display device: run install first' }
        # The virtual display first, so the session always has one.
        if ($vdd.Status -ne 'OK') { Enable-PnpDevice -InstanceId $vdd.InstanceId -Confirm:$false; Start-Sleep -Seconds 5 }
        $virtio = Get-Virtio
        if ($virtio -and $virtio.Status -eq 'OK') { Disable-PnpDevice -InstanceId $virtio.InstanceId -Confirm:$false; Start-Sleep -Seconds 5 }
        Show-Status
    }
    'virtio' {
        $virtio = Get-Virtio
        if ($virtio -and $virtio.Status -ne 'OK') { Enable-PnpDevice -InstanceId $virtio.InstanceId -Confirm:$false; Start-Sleep -Seconds 5 }
        $vdd = Get-Vdd
        if ($vdd -and $vdd.Status -eq 'OK') { Disable-PnpDevice -InstanceId $vdd.InstanceId -Confirm:$false; Start-Sleep -Seconds 5 }
        Show-Status
    }
    'single' {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class Displays {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct DISPLAY_DEVICE {
        public int cb;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string DeviceName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string DeviceString;
        public int StateFlags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string DeviceID;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string DeviceKey;
    }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct DEVMODE {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmDeviceName;
        public short dmSpecVersion, dmDriverVersion, dmSize, dmDriverExtra;
        public int dmFields, dmPositionX, dmPositionY, dmDisplayOrientation, dmDisplayFixedOutput;
        public short dmColor, dmDuplex, dmYResolution, dmTTOption, dmCollate;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmFormName;
        public short dmLogPixels;
        public int dmBitsPerPel, dmPelsWidth, dmPelsHeight, dmDisplayFlags, dmDisplayFrequency, dmICMMethod,
            dmICMIntent, dmMediaType, dmDitherType, dmReserved1, dmReserved2, dmPanningWidth, dmPanningHeight;
    }
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    public static extern bool EnumDisplayDevices(string device, int index, ref DISPLAY_DEVICE info, int flags);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    public static extern int ChangeDisplaySettingsEx(string device, ref DEVMODE mode, IntPtr hwnd, int flags, IntPtr param);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    public static extern int ChangeDisplaySettingsEx(string device, IntPtr mode, IntPtr hwnd, int flags, IntPtr param);
    // Detaches every display attached to the desktop except the primary; returns what it did, one line each.
    public static string KeepPrimaryOnly() {
        var log = new System.Text.StringBuilder();
        var info = new DISPLAY_DEVICE();
        for (int i = 0; ; i++) {
            info.cb = Marshal.SizeOf(info);
            if (!EnumDisplayDevices(null, i, ref info, 0)) break;
            bool attached = (info.StateFlags & 0x1) != 0, primary = (info.StateFlags & 0x4) != 0;
            if (!attached || primary) continue;
            var mode = new DEVMODE();
            mode.dmSize = (short)Marshal.SizeOf(mode);
            mode.dmFields = 0x20 | 0x80000 | 0x100000;  // DM_POSITION | DM_PELSWIDTH | DM_PELSHEIGHT, all 0: detach
            int r = ChangeDisplaySettingsEx(info.DeviceName, ref mode, IntPtr.Zero, 0x1 | 0x10000000, IntPtr.Zero);
            log.AppendLine("detach " + info.DeviceName + " (" + info.DeviceString + "): " + r);
        }
        log.AppendLine("apply: " + ChangeDisplaySettingsEx(null, IntPtr.Zero, IntPtr.Zero, 0, IntPtr.Zero));
        return log.ToString();
    }
}
'@
        [Displays]::KeepPrimaryOnly()
    }
    'status' { Show-Status }
}

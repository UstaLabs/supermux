# #136: the MSI's last wizard page gets a ticked "Launch supermux" box, and Finish starts the app.
#
# Compose always hands jpackage its own --resource-dir (cleared inside the task, after any freeArgs), so jpackage's
# main.wxs can't be overridden; the built MSI is edited instead, through Windows Installer's own COM API, before it
# is signed. Our MSI has WixUI_InstallDir's ExitDialog (the folder chooser is on), whose OptionalCheckBox shows when
# WIXUI_EXITDIALOGOPTIONALCHECKBOXTEXT is set and the product wasn't installed before this run.
#
# - Only the full wizard shows ExitDialog: /qn (admin, SYSTEM, Intune) and /passive (the in-app update, #113, whose
#   helper starts the app itself) never run this, so nothing opens twice and silent deployments open nothing.
# - The app is started through explorer.exe, which hands it to the signed-in user's shell: never elevated, even when
#   msiexec itself ran elevated. Async, no wait, errors ignored: a launch that fails never fails the install.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File msi-launch-after-install.ps1 -Msi <path>
param([Parameter(Mandatory = $true)][string]$Msi)
$ErrorActionPreference = "Stop"

$Action = "SupermuxLaunchApp"
# 34 = an exe run from a directory (Source: its working directory, Target: the command line);
# + 64 continue on error + 128 async, no wait.
$ActionType = 226
$Target = '"[WindowsFolder]explorer.exe" "[INSTALLDIR]supermux.exe"'
$Condition = "WIXUI_EXITDIALOGOPTIONALCHECKBOX = 1 AND NOT Installed"

$installer = New-Object -ComObject WindowsInstaller.Installer
function Invoke-Com($target, [string]$name, [object[]]$params = @()) {
    $target.GetType().InvokeMember($name, [Reflection.BindingFlags]::InvokeMethod, $null, $target, $params)
}
function Get-Prop($target, [string]$name, [object[]]$params = @()) {
    $target.GetType().InvokeMember($name, [Reflection.BindingFlags]::GetProperty, $null, $target, $params)
}
# Rows of a SELECT, each as an array of strings.
function Select-Rows($db, [string]$sql) {
    $view = Invoke-Com $db "OpenView" @($sql)
    Invoke-Com $view "Execute" | Out-Null
    $rows = @()
    while ($true) {
        $record = Invoke-Com $view "Fetch"
        if ($null -eq $record) { break }
        $count = Get-Prop $record "FieldCount"
        $rows += , @(1..$count | ForEach-Object { Get-Prop $record "StringData" @($_) })
    }
    Invoke-Com $view "Close" | Out-Null
    , $rows
}
function Run-Sql($db, [string]$sql) {
    $view = Invoke-Com $db "OpenView" @($sql)
    Invoke-Com $view "Execute" | Out-Null
    Invoke-Com $view "Close" | Out-Null
}
function Fail([string]$why) { throw "msi-launch-after-install: $why ($Msi)" }

$db = Invoke-Com $installer "OpenDatabase" @($Msi, 1) # msiOpenDatabaseModeTransact

# What the edit relies on, read from the MSI jpackage built.
if ((Select-Rows $db "SELECT ``Dialog`` FROM ``Dialog`` WHERE ``Dialog`` = 'ExitDialog'").Count -ne 1) { Fail "no ExitDialog" }
if ((Select-Rows $db "SELECT ``Control`` FROM ``Control`` WHERE ``Dialog_`` = 'ExitDialog' AND ``Control`` = 'OptionalCheckBox'").Count -ne 1) { Fail "ExitDialog has no OptionalCheckBox" }
if ((Select-Rows $db "SELECT ``Directory`` FROM ``Directory`` WHERE ``Directory`` = 'INSTALLDIR'").Count -ne 1) { Fail "no INSTALLDIR" }
# FileName is "short|long" when the long name isn't 8.3; MSI SQL has no LIKE.
$launchers = @((Select-Rows $db "SELECT ``FileName`` FROM ``File``") | Where-Object { ($_[0] -split '\|')[-1] -eq "supermux.exe" })
if ($launchers.Count -lt 1) { Fail "no supermux.exe" }
if ((Select-Rows $db "SELECT ``Action`` FROM ``CustomAction`` WHERE ``Action`` = '$Action'").Count -ne 0) { Fail "already has $Action" }

Run-Sql $db "INSERT INTO ``Property`` (``Property``, ``Value``) VALUES ('WIXUI_EXITDIALOGOPTIONALCHECKBOXTEXT', 'Launch supermux')"
Run-Sql $db "INSERT INTO ``Property`` (``Property``, ``Value``) VALUES ('WIXUI_EXITDIALOGOPTIONALCHECKBOX', '1')"
Run-Sql $db "INSERT INTO ``CustomAction`` (``Action``, ``Type``, ``Source``, ``Target``) VALUES ('$Action', $ActionType, 'INSTALLDIR', '$Target')"
# Ordering 1: before Finish's own EndDialog.
Run-Sql $db "INSERT INTO ``ControlEvent`` (``Dialog_``, ``Control_``, ``Event``, ``Argument``, ``Condition``, ``Ordering``) VALUES ('ExitDialog', 'Finish', 'DoAction', '$Action', '$Condition', 1)"
Invoke-Com $db "Commit" | Out-Null
$db = $null
[GC]::Collect(); [GC]::WaitForPendingFinalizers()

# Read it back from disk: the build fails rather than ship an MSI that doesn't open the app.
$db = Invoke-Com $installer "OpenDatabase" @($Msi, 0) # msiOpenDatabaseModeReadOnly
$ca = Select-Rows $db "SELECT ``Type``, ``Source``, ``Target`` FROM ``CustomAction`` WHERE ``Action`` = '$Action'"
if ($ca.Count -ne 1 -or $ca[0][0] -ne "$ActionType" -or $ca[0][1] -ne "INSTALLDIR" -or $ca[0][2] -ne $Target) { Fail "the custom action didn't stick" }
$ev = Select-Rows $db "SELECT ``Condition`` FROM ``ControlEvent`` WHERE ``Dialog_`` = 'ExitDialog' AND ``Control_`` = 'Finish' AND ``Event`` = 'DoAction' AND ``Argument`` = '$Action'"
if ($ev.Count -ne 1 -or $ev[0][0] -ne $Condition) { Fail "the Finish event didn't stick" }
$props = Select-Rows $db "SELECT ``Property``, ``Value`` FROM ``Property`` WHERE ``Property`` = 'WIXUI_EXITDIALOGOPTIONALCHECKBOX' OR ``Property`` = 'WIXUI_EXITDIALOGOPTIONALCHECKBOXTEXT'"
if ($props.Count -ne 2) { Fail "the checkbox properties didn't stick" }
$db = $null
[GC]::Collect(); [GC]::WaitForPendingFinalizers()
Write-Host "msi-launch-after-install: ExitDialog launches supermux ($Msi)"

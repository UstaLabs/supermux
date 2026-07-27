[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BrokerPath,
    [Parameter(Mandatory = $true)]
    [string]$SessiondPath,
    [string]$MuxHome = (Join-Path $env:TEMP "supermux-windows-vm-smoke"),
    [int]$Port = 18791,
    [string]$Session = "",
    [string]$DeviceToken = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$BrokerPath = (Resolve-Path -LiteralPath $BrokerPath).Path
$SessiondPath = (Resolve-Path -LiteralPath $SessiondPath).Path
$baseUrl = "http://127.0.0.1:$Port"
$logPath = Join-Path $MuxHome "broker.log"
$script:broker = $null

function Wait-Host {
    param([int]$TimeoutSeconds = 30)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if ($script:broker -and $script:broker.HasExited) {
            $tail = if (Test-Path $logPath) { (Get-Content $logPath -Tail 50) -join "`n" } else { "(no log)" }
            throw "Broker exited with code $($script:broker.ExitCode).`n$tail"
        }
        try {
            $response = Invoke-RestMethod -Uri "$baseUrl/host" -TimeoutSec 2
            if ($response.hostId) { return $response }
        } catch {}
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "GET $baseUrl/host did not become ready in $TimeoutSeconds seconds"
}

function Start-SmokeBroker {
    New-Item -ItemType Directory -Force -Path $MuxHome, (Join-Path $MuxHome "state") | Out-Null
    $stubDir = Join-Path $MuxHome "stubbin"
    New-Item -ItemType Directory -Force -Path $stubDir | Out-Null
    "@echo off`r`npowershell.exe -NoProfile -Command `"Start-Sleep -Seconds 600`"`r`n" |
        Set-Content -LiteralPath (Join-Path $stubDir "claude.cmd") -Encoding Ascii

    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $BrokerPath
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.Environment["MUX_HOME"] = $MuxHome
    $start.Environment["MUX_STATE_DIR"] = (Join-Path $MuxHome "state")
    $start.Environment["MUX_WEB_PORT"] = [string]$Port
    $start.Environment["MUX_WEB_PUBLIC_URL"] = $baseUrl
    $start.Environment["MUX_SESSIOND_PATH"] = $SessiondPath
    $start.Environment["PATH"] = "$($start.Environment['PATH']);$stubDir"
    $script:broker = [Diagnostics.Process]::Start($start)
    $script:broker.StandardOutput.ReadToEndAsync().ContinueWith({
        param($task) Add-Content -LiteralPath $logPath -Value $task.Result
    }) | Out-Null
    $script:broker.StandardError.ReadToEndAsync().ContinueWith({
        param($task) Add-Content -LiteralPath $logPath -Value $task.Result
    }) | Out-Null
    return Wait-Host
}

function Stop-BrokerOnly {
    if ($script:broker -and -not $script:broker.HasExited) {
        Stop-Process -Id $script:broker.Id -Force
        $script:broker.WaitForExit(10000) | Out-Null
    }
}

function Wait-SessiondPipe {
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $pipes = @(Get-ChildItem \\.\pipe\ | Where-Object Name -Like "supermux-*")
        if ($pipes.Count -gt 0) { return $pipes.Name }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "No \\.\pipe\supermux-* named pipe appeared"
}

function Invoke-Api([string]$Method, [string]$Path, $Body = $null) {
    if (-not $DeviceToken) { throw "DeviceToken is required for authenticated checks" }
    $headers = @{ Authorization = "Bearer $DeviceToken" }
    $args = @{ Method = $Method; Uri = "$baseUrl$Path"; Headers = $headers; TimeoutSec = 10 }
    if ($null -ne $Body) {
        $args.ContentType = "application/json"
        $args.Body = ($Body | ConvertTo-Json -Compress)
    }
    return Invoke-RestMethod @args
}

function Open-Terminal([string]$SessionName, [string]$TerminalId = "vmprobe") {
    if (-not $DeviceToken) { throw "DeviceToken is required for the terminal WebSocket" }
    $socket = [Net.WebSockets.ClientWebSocket]::new()
    $socket.Options.SetRequestHeader("Authorization", "Bearer $DeviceToken")
    $sessionParam = [Uri]::EscapeDataString($SessionName)
    $uri = [Uri]"ws://127.0.0.1:$Port/ws/term?session=$sessionParam&terminal=$TerminalId"
    $connect = $socket.ConnectAsync($uri, [Threading.CancellationToken]::None)
    if (-not $connect.Wait(10000)) { $socket.Dispose(); throw "Terminal WebSocket connect timed out" }
    if ($connect.IsFaulted) { $socket.Dispose(); throw $connect.Exception }
    return $socket
}

function Send-TerminalText($Socket, [string]$Text) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Text)
    $task = $Socket.SendAsync(
        [ArraySegment[byte]]::new($bytes),
        [Net.WebSockets.WebSocketMessageType]::Text,
        $true,
        [Threading.CancellationToken]::None
    )
    if (-not $task.Wait(10000)) { throw "Terminal control-frame send timed out" }
}

function Send-TerminalBytes($Socket, [string]$Text) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Text)
    $task = $Socket.SendAsync(
        [ArraySegment[byte]]::new($bytes),
        [Net.WebSockets.WebSocketMessageType]::Binary,
        $true,
        [Threading.CancellationToken]::None
    )
    if (-not $task.Wait(10000)) { throw "Terminal input send timed out" }
}

function Wait-TerminalOutput($Socket, [string]$Pattern, [int]$TimeoutSeconds = 15) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $buffer = [byte[]]::new(65536)
    $output = [Text.StringBuilder]::new()
    while ([DateTime]::UtcNow -lt $deadline) {
        $remaining = [Math]::Max(1, [int]($deadline - [DateTime]::UtcNow).TotalMilliseconds)
        $receive = $Socket.ReceiveAsync(
            [ArraySegment[byte]]::new($buffer),
            [Threading.CancellationToken]::None
        )
        if (-not $receive.Wait($remaining)) { break }
        $result = $receive.Result
        if ($result.MessageType -eq [Net.WebSockets.WebSocketMessageType]::Close) { break }
        if ($result.MessageType -eq [Net.WebSockets.WebSocketMessageType]::Binary) {
            $chunk = [Text.Encoding]::UTF8.GetString($buffer, 0, $result.Count)
            $output.Append($chunk) | Out-Null
            if ($output.ToString() -match $Pattern) { return $output.ToString() }
        }
    }
    throw "Timed out waiting for terminal output /$Pattern/; capture=$($output.ToString())"
}

try {
    Remove-Item -LiteralPath $MuxHome -Recurse -Force -ErrorAction SilentlyContinue
    $hostInfo = Start-SmokeBroker
    Write-Host "HOST_ID=$($hostInfo.hostId)"
    Write-Host "BROKER_PID=$($script:broker.Id)"
    Write-Host "SESSIOND_PIPES=$((Wait-SessiondPipe) -join ',')"

    if (-not $Session) {
        Write-Host "SUPERMUX_WINDOWS_BOOT_OK"
        Write-Host "Authenticated terminal/restart checks skipped: pass -Session and -DeviceToken."
        exit 0
    }

    $encoded = [Uri]::EscapeDataString($Session)
    $listed = Invoke-Api GET "/api/term/list?session=$encoded"
    Write-Host "TERMINALS_BEFORE=$($listed.terminals.Count)"

    $terminal = Open-Terminal $Session
    Send-TerminalText $terminal '{"type":"resize","cols":120,"rows":40}'
    # Split the marker so input echo alone cannot satisfy the assertion.
    Send-TerminalBytes $terminal "Write-Output ('SUPERMUX_' + 'TERM_OK')`r"
    Wait-TerminalOutput $terminal "SUPERMUX_TERM_OK" | Out-Null
    $terminal.Dispose() # detach: the persistent target must remain alive

    $terminal = Open-Terminal $Session
    Wait-TerminalOutput $terminal "SUPERMUX_TERM_OK" | Out-Null
    $terminal.Dispose()
    Write-Host "SUPERMUX_WINDOWS_REATTACH_OK"

    # Verify persistence across a broker-only restart without killing the
    # independently owned sessiond or its detached terminal.
    $sessiondBefore = @(Get-Process mux-sessiond -ErrorAction Stop | Select-Object -ExpandProperty Id)
    Stop-BrokerOnly
    $hostInfo = Start-SmokeBroker
    $sessiondAfter = @(Get-Process mux-sessiond -ErrorAction Stop | Select-Object -ExpandProperty Id)
    if (Compare-Object $sessiondBefore $sessiondAfter) {
        throw "sessiond PID changed across broker-only restart: before=$sessiondBefore after=$sessiondAfter"
    }
    $terminal = Open-Terminal $Session
    Wait-TerminalOutput $terminal "SUPERMUX_TERM_OK" | Out-Null
    Send-TerminalText $terminal '{"type":"close"}'
    $terminal.Dispose()
    Write-Host "BROKER_RESTART_PID=$($script:broker.Id)"
    Write-Host "SESSIOND_PID=$($sessiondAfter -join ',')"
    Write-Host "SUPERMUX_WINDOWS_BROKER_RESTART_OK"
} finally {
    Stop-BrokerOnly
}

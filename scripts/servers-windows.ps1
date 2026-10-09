param([Parameter(Mandatory = $true)][ValidateSet('start', 'stop')][string]$Action)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskRuntime = Join-Path $taskRoot '.runtime'
$taskPidFile = Join-Path $taskRuntime 'frontend-windows.pid'
$taskIdentityFile = Join-Path $taskRuntime 'frontend-windows.json'
$taskVite = Join-Path $taskRoot 'frontend/node_modules/vite/bin/vite.js'
$taskConfig = Join-Path $taskRuntime 'vite-windows.config.mjs'
$taskResult = 0

function Write-Utf8([string]$Path, [string]$Text) {
    [IO.File]::WriteAllText($Path, $Text.Replace("`r`n", "`n"), (New-Object Text.UTF8Encoding($false)))
}

function Get-Frontend {
    if (-not (Test-Path -LiteralPath $taskPidFile)) { return $null }
    $serverId = 0
    if (-not [int]::TryParse((Get-Content -LiteralPath $taskPidFile -Raw).Trim(), [ref]$serverId) -or $serverId -le 0) {
        throw 'Frontend: invalid PID file.'
    }
    $server = Get-Process -Id $serverId -ErrorAction SilentlyContinue
    if (-not $server) { return $null }
    $details = Get-CimInstance Win32_Process -Filter "ProcessId = $serverId"
    $command = $details.CommandLine -replace '\\', '/'
    if ($details.Name -ne 'node.exe' -or
        -not $command.Contains('"' + ($taskVite -replace '\\', '/') + '"') -or
        -not $command.Contains('"' + ($taskConfig -replace '\\', '/') + '"')) {
        throw "Frontend: PID $serverId belongs to another process; leaving it running."
    }
    if (Test-Path -LiteralPath $taskIdentityFile) {
        $identity = Get-Content -LiteralPath $taskIdentityFile -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($identity.pid -ne $serverId -or
            $identity.started -ne $server.StartTime.ToUniversalTime().Ticks.ToString() -or
            $identity.root -ne $taskRoot) {
            throw 'Frontend: process identity changed; leaving it running.'
        }
    } else { throw 'Frontend: process identity file is missing; leaving it running.' }
    return $server
}

function Stop-Frontend {
    $server = Get-Frontend
    if ($server) {
        Stop-Process -InputObject $server
        if (-not $server.WaitForExit(10000)) { throw 'Frontend: shutdown timed out.' }
        Write-Host "Frontend stopped (PID $($server.Id))."
    } else { Write-Host 'Frontend: already stopped.' }
    foreach ($path in @($taskPidFile, $taskIdentityFile)) {
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path }
    }
}

function Invoke-Backend([string]$Operation) {
    if ($Operation -eq 'stop' -and -not (Test-Path -LiteralPath (Join-Path $taskRuntime 'backend.pid'))) {
        Write-Host 'Backend: no recorded process.'
        return
    }
    # WSL needs LF scripts. Keep generated copies under the ignored runtime directory.
    $source = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'servers.sh'))
    $source = $source.Replace('$ROOT/scripts/servers.sh', '$ROOT/.runtime/windows-servers.sh')
    $source = $source.Replace('"$ROOT/backend/gradlew"', 'bash "$ROOT/.runtime/windows-gradle/gradlew"')
    Write-Utf8 (Join-Path $taskRuntime 'windows-servers.sh') $source
    if ($Operation -eq 'start') {
        $wrapper = Join-Path $taskRuntime 'windows-gradle'
        New-Item -ItemType Directory -Force (Join-Path $wrapper 'gradle/wrapper') | Out-Null
        Write-Utf8 (Join-Path $wrapper 'gradlew') ([IO.File]::ReadAllText((Join-Path $taskRoot 'backend/gradlew')))
        Copy-Item (Join-Path $taskRoot 'backend/gradle/wrapper/gradle-wrapper.jar') (Join-Path $wrapper 'gradle/wrapper/')
        Copy-Item (Join-Path $taskRoot 'backend/gradle/wrapper/gradle-wrapper.properties') (Join-Path $wrapper 'gradle/wrapper/')
    }
    Write-Utf8 (Join-Path $taskRuntime 'windows-backend.sh') @'
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export GRADLE_USER_HOME="$PWD/.runtime/gradle-home"
exec bash .runtime/windows-servers.sh "$1" backend
'@
    & wsl.exe -d Ubuntu -- bash .runtime/windows-backend.sh $Operation
    if ($LASTEXITCODE -ne 0) { throw "Backend $Operation failed. See .runtime/backend.log." }
}

function Start-Frontend {
    $node = (Get-Command node.exe -ErrorAction Stop).Source
    if (-not (Test-Path -LiteralPath $taskVite)) {
        & npm.cmd ci --prefix (Join-Path $taskRoot 'frontend')
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
    }
    $addresses = & wsl.exe -d Ubuntu -- hostname -I
    if ($LASTEXITCODE -ne 0) { throw 'Cannot determine the WSL backend address.' }
    $address = (($addresses -join ' ') -split '\s+' | Where-Object { $_ -match '^\d{1,3}(\.\d{1,3}){3}$' } | Select-Object -First 1)
    if (-not $address) { throw 'WSL did not return an IPv4 address.' }
    $backendUrl = "http://${address}:8080"
    $health = Invoke-RestMethod "$backendUrl/actuator/health" -TimeoutSec 10
    if ($health.status -ne 'UP') { throw 'Backend is not ready.' }
    $server = Get-Frontend
    if (-not $server) {
        # Refuse to claim a port already occupied by another process.
        $probe = New-Object Net.Sockets.TcpClient
        try {
            $probe.Connect('127.0.0.1', 5173)
            throw 'Port 5173 is occupied by an unmanaged process.'
        } catch [Net.Sockets.SocketException] {
            # Connection refused: the port is available.
        } finally { $probe.Dispose() }
    }
    $config = @"
import base from '../frontend/vite.config.ts';
export default {
  ...base,
  server: { ...base.server, proxy: { ...base.server.proxy, '/api': '$backendUrl' } },
};
"@
    if (-not (Test-Path -LiteralPath $taskConfig) -or [IO.File]::ReadAllText($taskConfig) -ne $config.Replace("`r`n", "`n")) {
        Write-Utf8 $taskConfig $config
    }
    if (-not $server) {
        $arguments = '"{0}" "{1}" --config "{2}"' -f $taskVite, (Join-Path $taskRoot 'frontend'), $taskConfig
        $server = Start-Process -FilePath $node -ArgumentList $arguments -WorkingDirectory $taskRoot -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $taskRuntime 'frontend.log') `
            -RedirectStandardError (Join-Path $taskRuntime 'frontend-error.log') -PassThru
        Write-Utf8 $taskPidFile $server.Id.ToString()
    }
    Write-Utf8 $taskIdentityFile (@{pid=$server.Id; started=$server.StartTime.ToUniversalTime().Ticks.ToString(); root=$taskRoot} | ConvertTo-Json)
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if ($server.HasExited) { throw 'Frontend exited. See .runtime/frontend-error.log.' }
        try {
            $response = Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:5173/' -TimeoutSec 2
            if ($response.StatusCode -eq 200) {
                Write-Host "Frontend running (PID $($server.Id)): http://localhost:5173"
                Write-Host "Backend: $backendUrl"
                return
            }
        } catch { }
        Start-Sleep -Seconds 1
    }
    throw 'Frontend did not become ready. See .runtime/frontend-error.log.'
}

New-Item -ItemType Directory -Force $taskRuntime | Out-Null
$taskLock = $null
Push-Location -LiteralPath $taskRoot
try {
    # Serialize Windows start/stop commands, including frontend identity and config writes.
    $taskLock = [IO.File]::Open((Join-Path $taskRuntime 'windows-servers.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    if ($Action -eq 'start') {
        Invoke-Backend start
        Start-Frontend
    } else {
        try { Stop-Frontend } catch { Write-Host $_.Exception.Message -ForegroundColor Red; $taskResult = 1 }
        Invoke-Backend stop
    }
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    $taskResult = 1
} finally {
    if ($taskLock) { $taskLock.Dispose() }
    Pop-Location
}
exit $taskResult

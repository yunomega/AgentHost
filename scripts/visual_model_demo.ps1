[CmdletBinding()]
param(
    [string]$PackageName = 'com.example.agenthost',
    [string]$CommandText = '打开设置并点击网络',
    [string]$ProxyPlanUrl = 'http://127.0.0.1:8080/plan',
    [string]$AndroidPlanUrl = '',
    [string]$ProxyScriptPath = '',
    [string]$ProxyLogPath = (Join-Path $env:TEMP 'agenthost_visual_model_proxy.log'),
    [string]$ProxyErrorLogPath = (Join-Path $env:TEMP 'agenthost_visual_model_proxy.err.log'),
    [string]$UpstreamBaseUrl = $(if ($env:UPSTREAM_BASE_URL) { $env:UPSTREAM_BASE_URL } else { 'http://127.0.0.1:11434' }),
    [string]$UpstreamModel = $(if ($env:UPSTREAM_MODEL) { $env:UPSTREAM_MODEL } else { 'gelab-zero-4b-preview' }),
    [double]$UpstreamTimeoutSeconds = $(if ($env:UPSTREAM_TIMEOUT_SECONDS) { [double]$env:UPSTREAM_TIMEOUT_SECONDS } else { 30.0 }),
    [string]$ModelApiKey = 'dummy',
    [int]$ModelTimeoutMs = 30000,
    [int]$StartDelaySeconds = 2,
    [int]$SessionWaitSeconds = 45,
    [int]$PollIntervalSeconds = 2,
    [string]$AdbSerial = '',
    [switch]$KeepProxyRunning,
    [switch]$TailLogcat
)

$ErrorActionPreference = 'Stop'

$script:ResolvedScriptRoot = if ($PSScriptRoot) {
    $PSScriptRoot
} elseif ($PSCommandPath) {
    Split-Path -Parent $PSCommandPath
} else {
    (Get-Location).Path
}

if ([string]::IsNullOrWhiteSpace($ProxyScriptPath)) {
    $ProxyScriptPath = Join-Path $script:ResolvedScriptRoot 'proxy_server.py'
}

if ([string]::IsNullOrWhiteSpace($AndroidPlanUrl)) {
    $proxyUri = [Uri]$ProxyPlanUrl
    $AndroidPlanUrl = "http://127.0.0.1:$($proxyUri.Port)/plan"
}

$script:Receiver = "$PackageName/.debug.SessionAuditReceiver"
$script:AdbReverseSpec = "tcp:$(([Uri]$AndroidPlanUrl).Port)"
$script:StartedProxyProcess = $null
$script:ReusedExistingProxy = $false
$script:LogTags = @(
    'SessionAuditReceiver',
    'SessionCoordinator',
    'SessionAutomationEngine',
    'ModelPlanningAdapter',
    'HttpModelClient',
    'AgentHostPlatformClient'
)

function Write-Info([string]$Message) {
    Write-Host "[INFO] $Message" -ForegroundColor Cyan
}

function Write-Ok([string]$Message) {
    Write-Host "[ OK ] $Message" -ForegroundColor Green
}

function Write-Warn([string]$Message) {
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

function Get-PythonInvocation {
    $python = Get-Command python -ErrorAction SilentlyContinue
    if ($python) {
        return @{ FilePath = $python.Source; Args = @() }
    }

    $py = Get-Command py -ErrorAction SilentlyContinue
    if ($py) {
        return @{ FilePath = $py.Source; Args = @('-3') }
    }

    throw 'python/py not found in PATH'
}

function Invoke-Adb {
    param([string[]]$AdbArgs)

    $fullArgs = @()
    if (-not [string]::IsNullOrWhiteSpace($AdbSerial)) {
        $fullArgs += @('-s', $AdbSerial)
    }
    $fullArgs += $AdbArgs
    & adb @fullArgs
}

function Quote-RemoteArg {
    param([AllowNull()][string]$Value)

    if ($null -eq $Value) {
        return "''"
    }

    return "'" + ($Value -replace "'", "'`"'`"'") + "'"
}

function Build-RemoteCommand {
    param([string[]]$RemoteArgs)

    $parts = foreach ($arg in $RemoteArgs) {
        if ([string]::IsNullOrEmpty($arg)) {
            "''"
        } elseif ($arg -match '^[A-Za-z0-9_./:=,+-]+$') {
            $arg
        } else {
            Quote-RemoteArg -Value $arg
        }
    }

    return ($parts -join ' ')
}

function Invoke-AdbShell {
    param([string[]]$ShellArgs)

    $remoteCommand = Build-RemoteCommand -RemoteArgs $ShellArgs
    Invoke-Adb -AdbArgs @('shell', $remoteCommand)
}

function Get-HealthzUrl {
    $planUri = [Uri]$ProxyPlanUrl
    return ([Uri]::new($planUri, '/healthz')).AbsoluteUri
}

function Wait-ForProxyHealth {
    param([int]$TimeoutSeconds = 20)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $healthzUrl = Get-HealthzUrl
    while ((Get-Date) -lt $deadline) {
        try {
            return Invoke-RestMethod -Uri $healthzUrl -Method Get -TimeoutSec 2
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }

    throw "proxy health check timed out: $healthzUrl"
}

function Ensure-ProxyReady {
    $healthzUrl = Get-HealthzUrl
    try {
        $response = Invoke-RestMethod -Uri $healthzUrl -Method Get -TimeoutSec 2
        $script:ReusedExistingProxy = $true
        Write-Ok "reusing existing proxy: $healthzUrl"
        return $response
    } catch {
        Write-Info "proxy not ready yet, will start local proxy: $ProxyScriptPath"
    }

    if (-not (Test-Path $ProxyScriptPath)) {
        throw "proxy_server.py not found: $ProxyScriptPath"
    }

    $proxyUri = [Uri]$ProxyPlanUrl
    $env:PROXY_HOST = '127.0.0.1'
    $env:PROXY_PORT = [string]$proxyUri.Port
    $env:UPSTREAM_BASE_URL = $UpstreamBaseUrl
    $env:UPSTREAM_MODEL = $UpstreamModel
    $env:UPSTREAM_TIMEOUT_SECONDS = [string]$UpstreamTimeoutSeconds

    if (Test-Path $ProxyLogPath) {
        Remove-Item $ProxyLogPath -Force
    }
    if (Test-Path $ProxyErrorLogPath) {
        Remove-Item $ProxyErrorLogPath -Force
    }

    $python = Get-PythonInvocation
    $argumentList = @()
    $argumentList += $python.Args
    $argumentList += $ProxyScriptPath

    $startProcessArgs = @{
        FilePath = $python.FilePath
        ArgumentList = $argumentList
        WorkingDirectory = (Split-Path -Parent $ProxyScriptPath)
        RedirectStandardOutput = $ProxyLogPath
        RedirectStandardError = $ProxyErrorLogPath
        PassThru = $true
    }

    $script:StartedProxyProcess = Start-Process @startProcessArgs
    $response = Wait-ForProxyHealth
    Write-Ok "proxy ready: $healthzUrl"
    Write-Info "proxy stdout log: $ProxyLogPath"
    Write-Info "proxy stderr log: $ProxyErrorLogPath"
    return $response
}

function Setup-AdbReverse {
    Invoke-Adb -AdbArgs @('reverse', $script:AdbReverseSpec, $script:AdbReverseSpec) | Out-Null
    Write-Ok "adb reverse ready: $($script:AdbReverseSpec) -> $($script:AdbReverseSpec)"
}

function Stop-Session {
    try {
        Invoke-AdbShell -ShellArgs @(
            'am', 'broadcast',
            '-n', $script:Receiver,
            '-a', "$PackageName.debug.STOP_SESSION",
            '--ez', 'migrate', 'false'
        ) | Out-Null
    } catch {
        Write-Warn "stop session failed: $($_.Exception.Message)"
    }
}

function Clear-TaggedLogcat {
    Invoke-Adb -AdbArgs @('logcat', '-c') | Out-Null
}

function Start-DemoSession {
    $shellArgs = @(
        'am', 'broadcast',
        '-n', $script:Receiver,
        '-a', "$PackageName.debug.START_SESSION",
        '--es', 'command', $CommandText,
        '--es', 'planner_mode', 'MODEL',
        '--es', 'model_endpoint', $AndroidPlanUrl,
        '--es', 'model_api_key', $ModelApiKey,
        '--es', 'model_timeout_ms', [string]$ModelTimeoutMs,
        '--es', 'model_max_retries', '0',
        '--es', 'model_max_response_chars', '4096',
        '--es', 'session_display_mode', 'MAIN_DISPLAY',
        '--ez', 'visible_to_user', 'true',
        '--es', 'bootstrap_target', 'settings'
    )

    return (Invoke-AdbShell -ShellArgs $shellArgs | Out-String).Trim()
}

function Dump-ActiveSession {
    return (Invoke-AdbShell -ShellArgs @(
        'am', 'broadcast',
        '-n', $script:Receiver,
        '-a', "$PackageName.debug.DUMP_ACTIVE_SESSION"
    ) | Out-String).Trim()
}

function Wait-ForSessionToSettle {
    param(
        [int]$TimeoutSeconds = $SessionWaitSeconds,
        [int]$PollSeconds = $PollIntervalSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastDump = ''

    while ((Get-Date) -lt $deadline) {
        $lastDump = Dump-ActiveSession
        if ($lastDump -notmatch '"status":"RUNNING"') {
            return $lastDump
        }
        Start-Sleep -Seconds $PollSeconds
    }

    return $lastDump
}

function Show-RecentLogcat {
    $args = @('logcat', '-d', '-v', 'time', '-s') + $script:LogTags
    Invoke-Adb -AdbArgs $args
}

function Cleanup {
    if ($KeepProxyRunning) {
        return
    }
    if ($script:StartedProxyProcess) {
        try {
            Stop-Process -Id $script:StartedProxyProcess.Id -Force -ErrorAction Stop
            Write-Info "stopped proxy pid=$($script:StartedProxyProcess.Id)"
        } catch {
            Write-Warn "failed to stop proxy pid=$($script:StartedProxyProcess.Id): $($_.Exception.Message)"
        }
    }
}

try {
    $health = Ensure-ProxyReady
    Write-Info ("proxy healthz: model={0} upstream={1} timeout={2}" -f $health.model, $health.upstreamBaseUrl, $health.timeoutSeconds)

    Setup-AdbReverse
    Stop-Session
    Clear-TaggedLogcat

    $startResult = Start-DemoSession
    Write-Host $startResult

    Start-Sleep -Seconds $StartDelaySeconds

    if ($TailLogcat) {
        $dumpResult = Dump-ActiveSession
    } else {
        Write-Info "waiting for session status to leave RUNNING (timeout ${SessionWaitSeconds}s)"
        $dumpResult = Wait-ForSessionToSettle
    }
    Write-Host $dumpResult

    Write-Info "device model endpoint: $AndroidPlanUrl"
    Write-Info "current command: $CommandText"

    if ($TailLogcat) {
        Write-Info 'tailing logcat; press Ctrl+C to stop'
        $args = @('logcat', '-v', 'time', '-s') + $script:LogTags
        Invoke-Adb -AdbArgs $args
    } else {
        Write-Info 'recent logcat:'
        Show-RecentLogcat
        Write-Host ''
        Write-Host 'Next commands:' -ForegroundColor Cyan
        Write-Host '  adb logcat -v time -s SessionCoordinator SessionAutomationEngine ModelPlanningAdapter HttpModelClient AgentHostPlatformClient'
        Write-Host '  adb shell am broadcast -n com.example.agenthost/com.example.agenthost.debug.SessionAuditReceiver -a com.example.agenthost.debug.DUMP_ACTIVE_SESSION'
    }
} finally {
    Cleanup
}

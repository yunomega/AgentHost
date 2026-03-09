[CmdletBinding()]
param(
    [string]$PackageName = 'com.example.agenthost',
    [string]$ProxyPlanUrl = 'http://127.0.0.1:8080/plan',
    [string]$ProxyScriptPath = '',
    [string]$ProxyLogPath = (Join-Path $env:TEMP 'agenthost_proxy_real_model.log'),
    [string]$ProxyErrorLogPath = (Join-Path $env:TEMP 'agenthost_proxy_real_model.err.log'),
    [string]$UpstreamBaseUrl = $(if ($env:UPSTREAM_BASE_URL) { $env:UPSTREAM_BASE_URL } else { 'http://127.0.0.1:11434' }),
    [string]$UpstreamModel = $(if ($env:UPSTREAM_MODEL) { $env:UPSTREAM_MODEL } else { 'gelab-zero-4b-preview' }),
    [double]$UpstreamTimeoutSeconds = $(if ($env:UPSTREAM_TIMEOUT_SECONDS) { [double]$env:UPSTREAM_TIMEOUT_SECONDS } else { 30.0 }),
    [string]$ModelApiKey = 'dummy',
    [int]$ModelTimeoutMs = 30000,
    [int]$PollIntervalMs = 500,
    [int]$WaitTimeoutSeconds = 60,
    [int]$ActionTimeoutSeconds = 20,
    [int]$PostActionCooldownMs = 1200,
    [string]$ReplyText = '123456',
    [string[]]$Cases = @(),
    [string[]]$FlakyCases = @('done-or-ask', 'ask-if-needed', 'swipe', 'type-text'),
    [int]$FlakyCaseRetryCount = 1,
    [string]$AdbSerial = '',
    [switch]$KeepProxyRunning,
    [switch]$SkipLogcatClear
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

if ($Cases -and $Cases.Count -gt 0) {
    $normalizedCases = @()
    foreach ($caseItem in $Cases) {
        if ([string]::IsNullOrWhiteSpace($caseItem)) {
            continue
        }
        $normalizedCases += @(
            $caseItem.Split(',', [System.StringSplitOptions]::RemoveEmptyEntries) |
                ForEach-Object { $_.Trim() } |
                Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
        )
    }
    $Cases = $normalizedCases
}

if ($FlakyCases -and $FlakyCases.Count -gt 0) {
    $normalizedFlakyCases = @()
    foreach ($caseItem in $FlakyCases) {
        if ([string]::IsNullOrWhiteSpace($caseItem)) {
            continue
        }
        $normalizedFlakyCases += @(
            $caseItem.Split(',', [System.StringSplitOptions]::RemoveEmptyEntries) |
                ForEach-Object { $_.Trim().ToLowerInvariant() } |
                Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
        )
    }
    $FlakyCases = $normalizedFlakyCases
}

if ($FlakyCaseRetryCount -lt 0) {
    $FlakyCaseRetryCount = 0
}

if (-not $Cases -or $Cases.Count -eq 0) {
    $Cases = @(
        'done-or-ask',
        'click',
        'swipe',
        'type-text',
        'ask-if-needed',
        'force-ask-user',
        'invalid',
        'timeout',
        'unavailable-sim'
    )
}

$script:Receiver = "$PackageName/.debug.SessionAuditReceiver"
$script:AdbReverseSpec = 'tcp:8080'
$script:StartedProxyProcess = $null
$script:ReusedExistingProxy = $false
$script:LogTags = @(
    'SessionAuditReceiver',
    'SessionCoordinator',
    'SessionAutomationEngine',
    'PlannerSelector',
    'ModelPlanningAdapter',
    'HttpModelClient'
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

function Write-Fail([string]$Message) {
    Write-Host "[FAIL] $Message" -ForegroundColor Red
}

function Get-HealthzUrl {
    $planUri = [Uri]$ProxyPlanUrl
    return ([Uri]::new($planUri, '/healthz')).AbsoluteUri
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

function Write-AdbOutput {
    param([object]$Output)

    $text = ($Output | Out-String).TrimEnd()
    if (-not [string]::IsNullOrWhiteSpace($text)) {
        Write-Host $text
    }
}

function Get-TaggedLogcatText {
    $args = @('logcat', '-d', '-v', 'time', '-s') + $script:LogTags
    return (Invoke-Adb -AdbArgs $args | Out-String)
}

function Clear-TaggedLogcat {
    if ($SkipLogcatClear) {
        Write-Warn 'skip logcat clear by request'
        return
    }
    Invoke-Adb -AdbArgs @('logcat', '-c') | Out-Null
}

function Get-LatestRegexMatch {
    param(
        [string]$Text,
        [string]$Pattern
    )

    $regex = [regex]::new($Pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)
    $matches = $regex.Matches($Text)
    if ($matches.Count -eq 0) {
        return $null
    }
    return $matches[$matches.Count - 1]
}

function New-PlanUrl {
    param(
        [string]$Mode = '',
        [hashtable]$ExtraQuery = $null
    )

    $baseUri = [Uri]$ProxyPlanUrl
    $builder = [System.UriBuilder]::new($baseUri)
    $pairs = @()

    if (-not [string]::IsNullOrWhiteSpace($builder.Query)) {
        $pairs += $builder.Query.TrimStart('?')
    }
    if (-not [string]::IsNullOrWhiteSpace($Mode)) {
        $pairs += ('mode=' + [Uri]::EscapeDataString($Mode))
    }
    if ($ExtraQuery) {
        foreach ($key in $ExtraQuery.Keys) {
            $encodedKey = [Uri]::EscapeDataString(([string]$key))
            $encodedValue = [Uri]::EscapeDataString(([string]$ExtraQuery[$key]))
            $pairs += ([string]::Format('{0}={1}', $encodedKey, $encodedValue))
        }
    }

    $builder.Query = ($pairs -join '&')
    return $builder.Uri.AbsoluteUri
}

function Wait-ForProxyHealth {
    param([int]$TimeoutSeconds = 20)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $healthzUrl = Get-HealthzUrl
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-RestMethod -Uri $healthzUrl -Method Get -TimeoutSec 2
            return $response
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
    return $response
}

function Setup-AdbReverse {
    try {
        Invoke-Adb -AdbArgs @('reverse', $script:AdbReverseSpec, $script:AdbReverseSpec) | Out-Null
        Write-Ok "adb reverse ready: $($script:AdbReverseSpec) -> $($script:AdbReverseSpec)"
    } catch {
        throw "adb reverse failed: $($_.Exception.Message)"
    }
}

function Remove-AdbReverse {
    try {
        Invoke-Adb -AdbArgs @('reverse', '--remove', $script:AdbReverseSpec) | Out-Null
        Write-Info "removed adb reverse $($script:AdbReverseSpec)"
    } catch {
        Write-Warn "failed to remove adb reverse: $($_.Exception.Message)"
    }
}

function Start-ModelSession {
    param(
        [string]$CommandText,
        [string]$PlanUrl,
        [int]$TimeoutMs
    )

    $shellArgs = @(
        'am', 'broadcast',
        '-n', $script:Receiver,
        '-a', "$PackageName.debug.START_SESSION",
        '--es', 'command', $CommandText,
        '--es', 'planner_mode', 'MODEL',
        '--es', 'model_endpoint', $PlanUrl,
        '--es', 'model_api_key', $ModelApiKey,
        '--es', 'model_timeout_ms', [string]$TimeoutMs,
        '--es', 'model_max_retries', '0',
        '--es', 'model_max_response_chars', '4096'
    )

    Write-AdbOutput (Invoke-AdbShell -ShellArgs $shellArgs)
}

function Reply-ModelSession {
    param(
        [string]$SessionId,
        [string]$UserText
    )

    $shellArgs = @(
        'am', 'broadcast',
        '-n', $script:Receiver,
        '-a', "$PackageName.debug.REPLY_SESSION",
        '--es', 'session_id', $SessionId,
        '--es', 'user_text', $UserText
    )

    Write-AdbOutput (Invoke-AdbShell -ShellArgs $shellArgs)
}

function Stop-ModelSession {
    $shellArgs = @(
        'am', 'broadcast',
        '-n', $script:Receiver,
        '-a', "$PackageName.debug.STOP_SESSION",
        '--ez', 'migrate', 'false'
    )

    try {
        Invoke-AdbShell -ShellArgs $shellArgs | Out-Null
    } catch {
        Write-Warn "stop session failed: $($_.Exception.Message)"
    }
}

function Wait-ForSessionStart {
    param([int]$TimeoutSeconds = 10)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $logText = Get-TaggedLogcatText
        $match = Get-LatestRegexMatch -Text $logText -Pattern 'event=debug_start_session sessionId=([0-9a-f\-]+).* code=AUTOMATION_STARTED'
        if (-not $match) {
            $match = Get-LatestRegexMatch -Text $logText -Pattern 'event=start_session_begin sessionId=([0-9a-f\-]+)'
        }
        if ($match) {
            return [pscustomobject]@{
                Type = 'STARTED'
                SessionId = $match.Groups[1].Value
                Code = 'STARTED'
                LogText = $logText
            }
        }
        Start-Sleep -Milliseconds $PollIntervalMs
    }

    return [pscustomobject]@{
        Type = 'TIMEOUT'
        SessionId = $null
        Code = 'START_TIMEOUT'
        LogText = (Get-TaggedLogcatText)
    }
}

function Wait-ForSessionOutcome {
    param(
        [string]$SessionId,
        [int]$TimeoutSeconds = $WaitTimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $sessionPattern = [regex]::Escape($SessionId)
    while ((Get-Date) -lt $deadline) {
        $logText = Get-TaggedLogcatText

        $completed = Get-LatestRegexMatch -Text $logText -Pattern "event=session_completed sessionId=$sessionPattern.* code=COMPLETED"
        if ($completed) {
            return [pscustomobject]@{
                Type = 'COMPLETED'
                SessionId = $SessionId
                Code = 'COMPLETED'
                LogText = $logText
            }
        }

        $waiting = Get-LatestRegexMatch -Text $logText -Pattern "event=session_waiting_user sessionId=$sessionPattern.* code=WAITING_USER"
        if ($waiting) {
            return [pscustomobject]@{
                Type = 'WAITING_USER'
                SessionId = $SessionId
                Code = 'WAITING_USER'
                LogText = $logText
            }
        }

        $failed = Get-LatestRegexMatch -Text $logText -Pattern "event=session_failed sessionId=$sessionPattern.* code=([A-Z_]+)"
        if ($failed) {
            return [pscustomobject]@{
                Type = 'FAILED'
                SessionId = $SessionId
                Code = $failed.Groups[1].Value
                LogText = $logText
            }
        }

        Start-Sleep -Milliseconds $PollIntervalMs
    }

    return [pscustomobject]@{
        Type = 'TIMEOUT'
        SessionId = $SessionId
        Code = 'TIMEOUT'
        LogText = (Get-TaggedLogcatText)
    }
}

function Wait-ForActionExecution {
    param(
        [string]$SessionId,
        [string]$ActionType,
        [int]$TimeoutSeconds = $ActionTimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $sessionPattern = [regex]::Escape($SessionId)
    $actionPattern = [regex]::Escape($ActionType.ToUpperInvariant())
    while ((Get-Date) -lt $deadline) {
        $logText = Get-TaggedLogcatText

        $action = Get-LatestRegexMatch -Text $logText -Pattern "event=action_executed sessionId=$sessionPattern.* actionType=$actionPattern code=OK"
        if ($action) {
            return [pscustomobject]@{
                Type = 'ACTION_OK'
                SessionId = $SessionId
                Code = 'OK'
                LogText = $logText
            }
        }

        $failed = Get-LatestRegexMatch -Text $logText -Pattern "event=session_failed sessionId=$sessionPattern.* code=([A-Z_]+)"
        if ($failed) {
            return [pscustomobject]@{
                Type = 'FAILED'
                SessionId = $SessionId
                Code = $failed.Groups[1].Value
                LogText = $logText
            }
        }

        $waiting = Get-LatestRegexMatch -Text $logText -Pattern "event=session_waiting_user sessionId=$sessionPattern.* code=WAITING_USER"
        if ($waiting) {
            return [pscustomobject]@{
                Type = 'WAITING_USER'
                SessionId = $SessionId
                Code = 'WAITING_USER'
                LogText = $logText
            }
        }

        $completed = Get-LatestRegexMatch -Text $logText -Pattern "event=session_completed sessionId=$sessionPattern.* code=COMPLETED"
        if ($completed) {
            return [pscustomobject]@{
                Type = 'COMPLETED'
                SessionId = $SessionId
                Code = 'COMPLETED'
                LogText = $logText
            }
        }

        Start-Sleep -Milliseconds $PollIntervalMs
    }

    return [pscustomobject]@{
        Type = 'TIMEOUT'
        SessionId = $SessionId
        Code = 'ACTION_TIMEOUT'
        LogText = (Get-TaggedLogcatText)
    }
}

function New-CaseResult {
    param(
        [string]$Name,
        [string]$Outcome,
        [string]$Details,
        [bool]$Passed,
        [string]$SessionId = ''
    )

    return [pscustomobject]@{
        Name = $Name
        Outcome = $Outcome
        Details = $Details
        Passed = $Passed
        SessionId = $SessionId
    }
}

function Resolve-CaseConfig {
    param([string]$Name)

    switch ($Name.ToLowerInvariant()) {
        'done-or-ask' {
            return [pscustomobject]@{
                Name = $Name
                Kind = 'complete_or_reply'
                CommandText = 'If the screenshot is enough, output a single DONE JSON object. Otherwise ask me a short question.'
                PlanUrl = (New-PlanUrl)
                TimeoutMs = $ModelTimeoutMs
                ReplyText = $ReplyText
            }
        }
        'ask-if-needed' {
            return [pscustomobject]@{
                Name = $Name
                Kind = 'complete_or_reply'
                CommandText = 'Ask me for a 6-digit verification code if you need more information.'
                PlanUrl = (New-PlanUrl)
                TimeoutMs = $ModelTimeoutMs
                ReplyText = $ReplyText
            }
        }
        'ask-then-done' {
            return [pscustomobject]@{
                Name = $Name
                Kind = 'must_wait_then_complete'
                CommandText = 'Ask me once for a 6-digit verification code now. After I reply with any 6-digit code, immediately return DONE. Do not ask again. Do not wait. Do not click.'
                PlanUrl = (New-PlanUrl)
                TimeoutMs = $ModelTimeoutMs
                ReplyText = $ReplyText
            }
        }
        'force-ask-user' {
            return [pscustomobject]@{
                Name = $Name
                Kind = 'must_wait_then_complete'
                CommandText = 'You must ask me for a 6-digit verification code now. Return ASK_USER first. After I reply with any 6-digit code, immediately return DONE. Do not ask again. Do not wait. Do not click.'
                PlanUrl = (New-PlanUrl)
                TimeoutMs = $ModelTimeoutMs
                ReplyText = $ReplyText
            }
        }
        'click' {
            return [pscustomobject]@{
                Name = $Name
                Kind = 'action'
                CommandText = 'Tap near the center of the current screen exactly once. Return a single CLICK action with numeric x and y. Do not ask me anything. Do not wait.'
                PlanUrl = (New-PlanUrl)
                TimeoutMs = $ModelTimeoutMs
                ExpectedAction = 'CLICK'
            }
        }
        'swipe' {
            return [pscustomobject]@{
                Name = $Name
                Kind = 'action'
                CommandText = 'Return exactly one SWIPE action with x1=540, y1=1500, x2=540, y2=500, durationMs=400. Do not ask me anything. Do not click. Do not wait. Do not output any other action.'
                PlanUrl = (New-PlanUrl)
                TimeoutMs = $ModelTimeoutMs
                ExpectedAction = 'SWIPE'
            }
        }
        'type-text' {
            return [pscustomobject]@{
                Name = $Name
                Kind = 'action'
                CommandText = 'Type exactly hello123 now. Return a single TYPE_TEXT action with text set to hello123. Do not ask me anything. Do not click. Do not wait. Do not swipe.'
                PlanUrl = (New-PlanUrl)
                TimeoutMs = $ModelTimeoutMs
                ExpectedAction = 'TYPE_TEXT'
            }
        }
        'invalid' {
            return [pscustomobject]@{
                Name = $Name
                Kind = 'fail'
                CommandText = 'Smoke test invalid output handling.'
                PlanUrl = (New-PlanUrl -Mode 'invalid')
                TimeoutMs = $ModelTimeoutMs
                ExpectedFailureCode = 'MODEL_OUTPUT_INVALID'
            }
        }
        'timeout' {
            return [pscustomobject]@{
                Name = $Name
                Kind = 'fail'
                CommandText = 'Smoke test timeout handling.'
                PlanUrl = (New-PlanUrl -Mode 'timeout' -ExtraQuery @{
                    sleepSeconds = '8'
                    outputText = '{"action":"DONE","reason":"delayed_test_response"}'
                })
                TimeoutMs = 2000
                ExpectedFailureCode = 'MODEL_TIMEOUT'
            }
        }
        'unavailable-sim' {
            return [pscustomobject]@{
                Name = $Name
                Kind = 'fail'
                CommandText = 'Smoke test unavailable handling.'
                PlanUrl = (New-PlanUrl -Mode 'unavailable')
                TimeoutMs = $ModelTimeoutMs
                ExpectedFailureCode = 'MODEL_UNAVAILABLE'
            }
        }
        'upstream-down' {
            return [pscustomobject]@{
                Name = $Name
                Kind = 'fail'
                CommandText = 'Manual upstream-down smoke test. The upstream model server should already be stopped.'
                PlanUrl = (New-PlanUrl)
                TimeoutMs = $ModelTimeoutMs
                ExpectedFailureCode = 'MODEL_UNAVAILABLE'
            }
        }
        default {
            throw "unsupported case name: $Name"
        }
    }
}

function Run-CompletionCase {
    param([pscustomobject]$Case)

    Clear-TaggedLogcat
    Start-ModelSession -CommandText $Case.CommandText -PlanUrl $Case.PlanUrl -TimeoutMs $Case.TimeoutMs

    $start = Wait-ForSessionStart
    if ($start.Type -ne 'STARTED') {
        return (New-CaseResult -Name $Case.Name -Outcome $start.Code -Details 'session did not start' -Passed $false)
    }

    $sessionId = $start.SessionId
    $firstOutcome = Wait-ForSessionOutcome -SessionId $sessionId

    if ($Case.Kind -eq 'complete_or_reply') {
        switch ($firstOutcome.Type) {
            'COMPLETED' {
                return (New-CaseResult -Name $Case.Name -Outcome 'COMPLETED' -Details 'completed without reply' -Passed $true -SessionId $sessionId)
            }
            'WAITING_USER' {
                Write-Info "$($Case.Name) replying to sessionId=$sessionId"
                Clear-TaggedLogcat
                Reply-ModelSession -SessionId $sessionId -UserText $Case.ReplyText
                $secondOutcome = Wait-ForSessionOutcome -SessionId $sessionId
                if ($secondOutcome.Type -eq 'COMPLETED') {
                    return (New-CaseResult -Name $Case.Name -Outcome 'COMPLETED' -Details 'completed after reply' -Passed $true -SessionId $sessionId)
                }
                return (New-CaseResult -Name $Case.Name -Outcome $secondOutcome.Code -Details 'unexpected outcome after reply' -Passed $false -SessionId $sessionId)
            }
            'FAILED' {
                return (New-CaseResult -Name $Case.Name -Outcome $firstOutcome.Code -Details 'session_failed observed' -Passed $false -SessionId $sessionId)
            }
            default {
                return (New-CaseResult -Name $Case.Name -Outcome $firstOutcome.Code -Details 'no terminal outcome observed' -Passed $false -SessionId $sessionId)
            }
        }
    }

    switch ($firstOutcome.Type) {
        'WAITING_USER' {
            Write-Info "$($Case.Name) replying to sessionId=$sessionId"
            Clear-TaggedLogcat
            Reply-ModelSession -SessionId $sessionId -UserText $Case.ReplyText
            $secondOutcome = Wait-ForSessionOutcome -SessionId $sessionId
            if ($secondOutcome.Type -eq 'COMPLETED') {
                return (New-CaseResult -Name $Case.Name -Outcome 'COMPLETED' -Details 'completed after required reply' -Passed $true -SessionId $sessionId)
            }
            return (New-CaseResult -Name $Case.Name -Outcome $secondOutcome.Code -Details 'unexpected outcome after required reply' -Passed $false -SessionId $sessionId)
        }
        'COMPLETED' {
            return (New-CaseResult -Name $Case.Name -Outcome 'COMPLETED' -Details 'completed without required ASK_USER' -Passed $false -SessionId $sessionId)
        }
        'FAILED' {
            return (New-CaseResult -Name $Case.Name -Outcome $firstOutcome.Code -Details 'session_failed observed before ASK_USER' -Passed $false -SessionId $sessionId)
        }
        default {
            return (New-CaseResult -Name $Case.Name -Outcome $firstOutcome.Code -Details 'no terminal outcome observed' -Passed $false -SessionId $sessionId)
        }
    }
}

function Run-ActionCase {
    param([pscustomobject]$Case)

    Clear-TaggedLogcat
    Start-ModelSession -CommandText $Case.CommandText -PlanUrl $Case.PlanUrl -TimeoutMs $Case.TimeoutMs

    $start = Wait-ForSessionStart
    if ($start.Type -ne 'STARTED') {
        return (New-CaseResult -Name $Case.Name -Outcome $start.Code -Details 'session did not start' -Passed $false)
    }

    $sessionId = $start.SessionId
    $actionOutcome = Wait-ForActionExecution -SessionId $sessionId -ActionType $Case.ExpectedAction
    switch ($actionOutcome.Type) {
        'ACTION_OK' {
            return (New-CaseResult -Name $Case.Name -Outcome 'ACTION_OK' -Details "$($Case.ExpectedAction) executed" -Passed $true -SessionId $sessionId)
        }
        'FAILED' {
            return (New-CaseResult -Name $Case.Name -Outcome $actionOutcome.Code -Details 'session_failed observed before expected action' -Passed $false -SessionId $sessionId)
        }
        'WAITING_USER' {
            return (New-CaseResult -Name $Case.Name -Outcome 'WAITING_USER' -Details 'model asked user instead of executing action' -Passed $false -SessionId $sessionId)
        }
        'COMPLETED' {
            return (New-CaseResult -Name $Case.Name -Outcome 'COMPLETED' -Details 'completed without executing expected action' -Passed $false -SessionId $sessionId)
        }
        default {
            return (New-CaseResult -Name $Case.Name -Outcome $actionOutcome.Code -Details 'expected action was not observed' -Passed $false -SessionId $sessionId)
        }
    }
}

function Run-FailureCase {
    param([pscustomobject]$Case)

    Clear-TaggedLogcat
    Start-ModelSession -CommandText $Case.CommandText -PlanUrl $Case.PlanUrl -TimeoutMs $Case.TimeoutMs

    $start = Wait-ForSessionStart
    if ($start.Type -ne 'STARTED') {
        return (New-CaseResult -Name $Case.Name -Outcome $start.Code -Details 'session did not start' -Passed $false)
    }

    $sessionId = $start.SessionId
    $outcome = Wait-ForSessionOutcome -SessionId $sessionId
    if ($outcome.Type -eq 'FAILED' -and $outcome.Code -eq $Case.ExpectedFailureCode) {
        return (New-CaseResult -Name $Case.Name -Outcome $outcome.Code -Details 'expected failure observed' -Passed $true -SessionId $sessionId)
    }
    if ($outcome.Type -eq 'FAILED') {
        return (New-CaseResult -Name $Case.Name -Outcome $outcome.Code -Details "unexpected failure code, expected $($Case.ExpectedFailureCode)" -Passed $false -SessionId $sessionId)
    }
    return (New-CaseResult -Name $Case.Name -Outcome $outcome.Code -Details "expected failure $($Case.ExpectedFailureCode) was not observed" -Passed $false -SessionId $sessionId)
}

function Invoke-CaseOnce {
    param([pscustomobject]$Case)

    try {
        switch ($Case.Kind) {
            'complete_or_reply' { return (Run-CompletionCase -Case $Case) }
            'must_wait_then_complete' { return (Run-CompletionCase -Case $Case) }
            'action' { return (Run-ActionCase -Case $Case) }
            'fail' { return (Run-FailureCase -Case $Case) }
            default { throw "unsupported case kind: $($Case.Kind)" }
        }
    } finally {
        Stop-ModelSession
        Start-Sleep -Milliseconds 400
    }
}

function Wait-ForSessionSettled {
    param(
        [string]$SessionId,
        [int]$TimeoutSeconds = 6
    )

    if ([string]::IsNullOrWhiteSpace($SessionId)) {
        Start-Sleep -Milliseconds 400
        return
    }

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $sessionPattern = [regex]::Escape($SessionId)
    while ((Get-Date) -lt $deadline) {
        $logText = Get-TaggedLogcatText
        $closed = Get-LatestRegexMatch -Text $logText -Pattern "event=session_closed sessionId=$sessionPattern.* code=CLOSED"
        if ($closed) {
            return
        }
        $debugClosed = Get-LatestRegexMatch -Text $logText -Pattern "event=debug_stop_session sessionId=$sessionPattern.* code=CLOSED"
        if ($debugClosed) {
            return
        }
        Start-Sleep -Milliseconds $PollIntervalMs
    }
}

function Run-Case {
    param([string]$Name)

    $case = Resolve-CaseConfig -Name $Name
    $normalizedName = $case.Name.ToLowerInvariant()
    $isFlakyCase = ($case.Kind -ne 'fail') -and ($FlakyCaseRetryCount -gt 0) -and ($FlakyCases -contains $normalizedName)
    $maxAttempts = if ($isFlakyCase) { 1 + $FlakyCaseRetryCount } else { 1 }

    for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
        if ($attempt -eq 1) {
            Write-Info "case=$($case.Name)"
        } else {
            Write-Warn "retry case=$($case.Name) attempt=$attempt/$maxAttempts"
        }

        $result = Invoke-CaseOnce -Case $case
        Wait-ForSessionSettled -SessionId $result.SessionId
        if ($case.Kind -eq 'action' -and $PostActionCooldownMs -gt 0) {
            Start-Sleep -Milliseconds $PostActionCooldownMs
        }

        if ($result.Passed) {
            if ($attempt -gt 1) {
                $result.Details = "$($result.Details) (passed on retry $attempt/$maxAttempts)"
            }
            return $result
        }

        if ($attempt -ge $maxAttempts) {
            if ($maxAttempts -gt 1) {
                $result.Details = "$($result.Details) (after $maxAttempts attempts)"
            }
            return $result
        }

        Start-Sleep -Milliseconds 800
    }
}

function Scan-ProxyLogIssues {
    if (-not (Test-Path $ProxyLogPath)) {
        Write-Warn "proxy log not found, skip JSON scan: $ProxyLogPath"
        return @()
    }

    $issues = @()
    foreach ($line in Get-Content $ProxyLogPath) {
        if ($line -notmatch 'sanitized outputText (.+)$') {
            continue
        }

        $jsonText = $Matches[1].Trim()
        try {
            $payload = $jsonText | ConvertFrom-Json -ErrorAction Stop
        } catch {
            continue
        }

        $propNames = @($payload.PSObject.Properties.Name)
        switch ([string]$payload.action) {
            'WAIT' {
                if (($propNames -contains 'value') -and -not ($propNames -contains 'durationMs')) {
                    $issues += [pscustomobject]@{ Kind = 'WAIT_VALUE'; Json = $jsonText }
                }
            }
            'CLICK' {
                if (-not ($propNames -contains 'x') -or -not ($propNames -contains 'y')) {
                    $issues += [pscustomobject]@{ Kind = 'CLICK_MISSING_COORDS'; Json = $jsonText }
                }
            }
            'ASK_USER' {
                $question = if ($propNames -contains 'question') { [string]$payload.question } else { '' }
                if ([string]::IsNullOrWhiteSpace($question)) {
                    $issues += [pscustomobject]@{ Kind = 'ASK_USER_MISSING_QUESTION'; Json = $jsonText }
                }
            }
            'TYPE_TEXT' {
                $text = if ($propNames -contains 'text') { [string]$payload.text } else { '' }
                if ([string]::IsNullOrWhiteSpace($text)) {
                    $issues += [pscustomobject]@{ Kind = 'TYPE_TEXT_MISSING_TEXT'; Json = $jsonText }
                }
            }
        }
    }

    return $issues
}

function Get-ProxyTraceForSession {
    param([string]$SessionId)

    if ([string]::IsNullOrWhiteSpace($SessionId)) {
        return @()
    }
    if (-not (Test-Path $ProxyLogPath)) {
        return @()
    }

    $lines = @(Get-Content $ProxyLogPath)
    $needle = '"sessionId":"' + $SessionId + '"'
    $startIndex = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i].Contains('request summary ') -and $lines[$i].Contains($needle)) {
            $startIndex = $i
            break
        }
    }
    if ($startIndex -lt 0) {
        return @()
    }

    $trace = @()
    for ($i = $startIndex; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        if ($i -gt $startIndex -and $line.Contains('request summary ')) {
            break
        }
        if (
            $line.Contains('request summary ') -or
            $line.Contains('upstream request summary ') -or
            $line.Contains('upstream response ') -or
            $line.Contains('upstream model text ') -or
            $line.Contains('sanitized outputText ') -or
            $line.Contains('sanitized output invalid ') -or
            $line.Contains('upstream request failed ') -or
            $line.Contains('test mode ')
        ) {
            $trace += $line
        }
    }

    return $trace
}

function Stop-StartedProxy {
    if ($KeepProxyRunning) {
        Write-Warn 'keep proxy running by request'
        return
    }

    if ($script:StartedProxyProcess -and -not $script:StartedProxyProcess.HasExited) {
        Write-Info "stopping proxy pid=$($script:StartedProxyProcess.Id)"
        Stop-Process -Id $script:StartedProxyProcess.Id -Force
    }
}

$caseResults = @()
$proxyIssues = @()

try {
    $health = Ensure-ProxyReady
    Write-Info ("proxy healthz: model={0} upstream={1} timeout={2}" -f $health.model, $health.upstreamBaseUrl, $health.timeoutSeconds)

    Setup-AdbReverse

    Write-Info ("cases={0}" -f ($Cases -join ','))
    Write-Info ("flakyCases={0} retryCount={1}" -f ($FlakyCases -join ','), $FlakyCaseRetryCount)
    Write-Info ("postActionCooldownMs={0}" -f $PostActionCooldownMs)

    foreach ($caseName in $Cases) {
        $caseResults += Run-Case -Name $caseName
    }

    $proxyIssues = Scan-ProxyLogIssues

    Write-Host ''
    Write-Host '=== Case Summary ===' -ForegroundColor White
    foreach ($result in $caseResults) {
        $color = if ($result.Passed) { 'Green' } else { 'Yellow' }
        Write-Host ("{0,-16} {1,-18} {2}" -f $result.Name, $result.Outcome, $result.Details) -ForegroundColor $color
    }

    $failedResults = @($caseResults | Where-Object { -not $_.Passed })
    if ($failedResults.Count -gt 0) {
        Write-Host ''
        Write-Host '=== Failure Details ===' -ForegroundColor White
        foreach ($result in $failedResults) {
            Write-Warn ("{0}: outcome={1} sessionId={2}" -f $result.Name, $result.Outcome, $result.SessionId)
            $traceLines = @(Get-ProxyTraceForSession -SessionId $result.SessionId)
            if ($traceLines.Count -eq 0) {
                Write-Warn 'proxy trace not found for session'
                continue
            }
            foreach ($traceLine in $traceLines) {
                Write-Host $traceLine -ForegroundColor DarkGray
            }
        }
    }

    Write-Host ''
    Write-Host '=== Proxy JSON Scan ===' -ForegroundColor White
    if ($proxyIssues.Count -eq 0) {
        Write-Ok 'no WAIT+value / CLICK missing coords / ASK_USER missing question / TYPE_TEXT missing text detected'
    } else {
        foreach ($issue in $proxyIssues) {
            Write-Warn ("{0}: {1}" -f $issue.Kind, $issue.Json)
        }
    }

    Write-Host ''
    Write-Info "proxy log: $ProxyLogPath"
    if (Test-Path $ProxyErrorLogPath) {
        Write-Info "proxy stderr log: $ProxyErrorLogPath"
    }

    $hasFailedCase = @($caseResults | Where-Object { -not $_.Passed }).Count -gt 0
    if ($hasFailedCase -or $proxyIssues.Count -gt 0) {
        exit 1
    }
    exit 0
} finally {
    Stop-ModelSession
    Remove-AdbReverse
    Stop-StartedProxy
}

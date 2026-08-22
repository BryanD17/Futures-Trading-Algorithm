<#
.SYNOPSIS
    Supervisor for the LIVE trading engine. Designed to be launched by a
    Scheduled Task at system startup so the Discord feed survives a reboot.

.DESCRIPTION
    Responsibilities, in order:

      1. SAFETY GATE. Refuses to auto-start unless the bound Topstep account is
         a practice account. Auto-starting LIVE is only acceptable while the
         account is simulated; the day credentials point at a funded account,
         this must stop and wait for a human. It logs loudly and exits rather
         than trading something nobody chose to trade at boot.
      2. Loads the Discord webhook(s) from ~/.topstep/notify.properties so the
         URL never appears in the Scheduled Task definition, which is readable
         by anyone who opens Task Scheduler or exports the task XML.
      3. Holds a system wake-lock, because a machine that falls asleep stops
         the feed exactly as thoroughly as one that reboots.
      4. Launches the JVM, waits for the API, and starts LIVE mode.
      5. Supervises. If the JVM dies, this exits non-zero so the Scheduled
         Task's restart policy takes over.

    Secrets are read from disk and passed via the child process environment.
    They are never written to the log, never echoed, and never command-line
    arguments (which are visible in the process list).

.NOTES
    Stop it with:  Stop-ScheduledTask -TaskName "CertifiedTraders-Engine"
    Disable it:    Disable-ScheduledTask -TaskName "CertifiedTraders-Engine"
#>

param(
    [string]$Repo     = "C:\Users\Owner\Futures-Trading-Algorithm",
    [string]$UserHome = "C:\Users\Owner",
    [int]$Port        = 8080,
    [switch]$NoStart          # launch the JVM but do not enter LIVE mode
)

$ErrorActionPreference = "Stop"

$logDir = Join-Path $Repo "logs"
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Force $logDir | Out-Null }
$stamp     = Get-Date -Format "yyyyMMdd-HHmmss"
$superLog  = Join-Path $logDir "supervisor.log"
$engineOut = Join-Path $logDir "engine-$stamp.out.log"
$engineErr = Join-Path $logDir "engine-$stamp.err.log"

function Log($msg) {
    $line = "{0}  {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $msg
    Write-Output $line
    Add-Content -Path $superLog -Value $line -Encoding utf8
}

Log "=== supervisor starting (repo=$Repo port=$Port) ==="

# ── 1. SAFETY GATE ────────────────────────────────────────────────────────
# Never auto-start LIVE against a non-practice account.
$credFile = Join-Path $UserHome ".topstep\credentials.properties"
if (-not (Test-Path $credFile)) {
    Log "FATAL: $credFile not found. Refusing to start."
    exit 1
}
$accountId = (Get-Content $credFile | Where-Object { $_ -match '^\s*accountId\s*=' } |
              Select-Object -First 1) -replace '^\s*accountId\s*=\s*', ''
$accountId = $accountId.Trim()
if ([string]::IsNullOrWhiteSpace($accountId)) {
    Log "FATAL: no accountId in credentials file. Refusing to start."
    exit 1
}
if ($accountId -notmatch '^PRAC') {
    Log "REFUSING TO AUTO-START: bound account '$accountId' is not a practice"
    Log "account. Auto-starting LIVE on a funded account is not something a"
    Log "reboot should decide. Start it by hand if that is genuinely intended."
    exit 1
}
Log "safety gate OK - account '$accountId' is a practice account"

# ── 2. SECRETS ────────────────────────────────────────────────────────────
$notifyFile = Join-Path $UserHome ".topstep\notify.properties"
$notify = @{}
if (Test-Path $notifyFile) {
    Get-Content $notifyFile | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.+)$') {
            $notify[$Matches[1].Trim()] = $Matches[2].Trim()
        }
    }
    $names = ($notify.Keys | Sort-Object) -join ", "
    Log "loaded notify config: $names (values redacted)"
} else {
    Log "no $notifyFile - engine will run with alerts DISABLED (trading unaffected)"
}

# ── 3. WAKE LOCK ──────────────────────────────────────────────────────────
# Display sleep is deliberately still allowed; only system sleep is blocked.
try {
    Add-Type -Namespace Win32Sup -Name Power -MemberDefinition @'
[DllImport("kernel32.dll", SetLastError = true)]
public static extern uint SetThreadExecutionState(uint esFlags);
'@
    # NOT 0x80000000: PowerShell parses that as a signed Int32, which overflows
    # to -2147483648 and then fails the [uint32] cast. Decimal avoids the trap.
    $ES_CONTINUOUS = [uint32]2147483648
    $ES_SYSTEM     = [uint32]1
    if ([Win32Sup.Power]::SetThreadExecutionState($ES_CONTINUOUS -bor $ES_SYSTEM) -ne 0) {
        Log "wake-lock held (system sleep blocked while this runs)"
    } else {
        Log "WARN: could not set wake-lock; the machine may sleep and stop the feed"
    }
} catch {
    Log "WARN: wake-lock unavailable: $($_.Exception.Message)"
}

# ── 4. LAUNCH ─────────────────────────────────────────────────────────────
$jar = Join-Path $Repo "api-backend\build\libs\api-backend-1.0.0-SNAPSHOT.jar"
if (-not (Test-Path $jar)) { Log "FATAL: jar not found at $jar"; exit 1 }

$java = "java"
try { $java = (Get-Command java -ErrorAction Stop).Source } catch {
    $fallback = "C:\Program Files\Eclipse Adoptium\jdk-21\bin\java.exe"
    if (Test-Path $fallback) { $java = $fallback } else { Log "FATAL: java not on PATH"; exit 1 }
}
Log "java: $java"

# Secrets go into THIS process's environment, which the child inherits.
# They are never passed as arguments, which would expose them in the process list.
foreach ($k in $notify.Keys) { Set-Item -Path "Env:$k" -Value $notify[$k] }

# -Duser.home matters: under a Scheduled Task running as SYSTEM the JVM would
# otherwise resolve ~/.topstep to the system profile and fail to find credentials.
$jvmArgs = @(
    "-Duser.home=$UserHome"
    "-Dserver.port=$Port"
    "-Dbackfill.days=7"
    "-Dstdvote.displacement.recentBars=12"
    "-Dstdvote.displacement.atrMult=1.2"
    "-Dstdvote.displacement.bodyPct=0.55"
    "-jar", $jar
)

$proc = Start-Process -FilePath $java -ArgumentList $jvmArgs -WorkingDirectory $Repo `
        -RedirectStandardOutput $engineOut -RedirectStandardError $engineErr `
        -PassThru -WindowStyle Hidden
Log "JVM started, PID $($proc.Id), log $engineOut"

# ── 5. WAIT FOR API, THEN GO LIVE ─────────────────────────────────────────
$ready = $false
for ($i = 0; $i -lt 90; $i++) {
    Start-Sleep -Seconds 2
    if ($proc.HasExited) { Log "FATAL: JVM exited during startup (code $($proc.ExitCode))"; exit 1 }
    try {
        Invoke-RestMethod "http://localhost:$Port/api/control/status" -TimeoutSec 5 | Out-Null
        $ready = $true; break
    } catch { }
}
if (-not $ready) { Log "FATAL: API never came up"; try { $proc.Kill() } catch {}; exit 1 }
Log "API is up"

if ($NoStart) {
    Log "-NoStart given: leaving the engine idle, not entering LIVE"
} else {
    try {
        Invoke-RestMethod "http://localhost:$Port/api/control/start?mode=LIVE" `
            -Method Post -TimeoutSec 300 | Out-Null
        Log "LIVE mode started"
    } catch {
        Log "ERROR starting LIVE: $($_.Exception.Message)"
    }
}

# ── 6. SUPERVISE ──────────────────────────────────────────────────────────
# Staying alive keeps the wake-lock held. Exiting non-zero on a JVM death lets
# the Scheduled Task restart policy do its job.
Log "supervising PID $($proc.Id)"
while (-not $proc.HasExited) { Start-Sleep -Seconds 30 }
Log "JVM exited with code $($proc.ExitCode) - supervisor exiting so the task can restart it"
exit 1

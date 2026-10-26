<#
.SYNOPSIS
  About 25 minute fast stability test for tv.frostline.app. After every action it forces a GC (frostline://debug/gc), waits
  5 s and records post-GC PSS (total/java/native/graphics), threads, fds, CPU and whether the process is alive, to stress.csv.
  Only the final summary (about 25 lines) goes to the console; it is also written to summary.txt.

.DESCRIPTION
  Needs Settings > Debug overlay ON in the app (debug deep links are ignored otherwise) and a live stream open.
  Phases: 1 baseline (2 min), 2 ten channel switches 90 s apart, 3 six chat-toggle cycles 15 s apart, 4 four open-vod and back
  round trips of 30 s, 5 three reconnects (IRC + Hermes), 6 three refresh-token + refresh-integrity 60 s apart.
  The baseline is the MAX of the phase-1 samples. A phase fails only if its END post-GC PSS is more than baseline + 10 MB (threads/fds: +5); peaks are reported, never fail a phase. The overall verdict also fails if the PSS slope is above +0.5 MB per step or on a crash. A drop is fine (one-sided rules). The logic lives in stressverdict.ps1 (tested by stressverdict.tests.ps1).
  An offline channel is not detected: pick -Channels that are live.
  Reads from the TV, plus `am start` deep links. Does not install anything.
#>
param(
    [string]$TvHost = '192.168.68.54',
    [int]$Port = 5555,
    [string]$Package = 'tv.frostline.app',
    [string[]]$Channels = @('xqc', 'shroud', 'summit1g', 'caedrel', 'tarik', 'lirik'),
    [string]$TvPy = 'C:\Users\karth\Documents\repos\cleanyourtv\tools\tv.py',
    [string]$Python = 'C:\Users\karth\Documents\repos\cleanyourtv\tools\venv\Scripts\python.exe',
    [string]$OutDir = (Join-Path $PSScriptRoot ("..\build\stress\" + (Get-Date -Format 'yyyyMMdd-HHmmss')))
)

$ErrorActionPreference = 'Continue'
if (-not (Test-Path $TvPy)) { throw "tv.py not found at $TvPy" }
if (-not (Test-Path $Python)) { throw "python not found at $Python" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$env:TV_HOST = $TvHost
$env:TV_PORT = "$Port"
. (Join-Path $PSScriptRoot 'tvlib.ps1')
. (Join-Path $PSScriptRoot 'stressverdict.ps1')

$csv = Join-Path $OutDir 'stress.csv'
$log = Join-Path $OutDir 'stress.log'
'step,phase,action,time,pss_mb,java_mb,native_mb,graphics_mb,threads,fds,cpu_pct,alive' | Set-Content $csv

$appPid = Get-AppPid
if (-not $appPid) { throw "$Package is not running on $TvHost. Start the app and a stream first." }
$startPid = $appPid
# All mutable state lives in one object: `$script:` variables are not the same variables when this file is dot-sourced
# (`& { . .\tools\stress.ps1 ... }` from Start-Process), which left the rows empty and the summary at 0 steps.
$st = @{ Rows = (New-Object System.Collections.Generic.List[object]); Crashes = 0; Step = 0; Pid = $appPid }

function Send-Link([string]$Uri) { Invoke-Tv @("am start -a android.intent.action.VIEW -d $Uri $Package") | Out-Null }

# GC, wait, measure, record.
function Measure-Step([int]$Phase, [string]$Action) {
    $st.Step++
    Send-Link 'frostline://debug/gc'
    Start-Sleep -Seconds 5
    $s = Get-Sample $st.Pid
    if ($s.Alive -and $s.Pid -ne $st.Pid) { $st.Crashes++; $st.Pid = $s.Pid; "$(Get-Date -Format o) pid changed at step $($st.Step) ($Action)" | Add-Content $log }
    if (-not $s.Alive) { $st.Crashes++; "$(Get-Date -Format o) process gone at step $($st.Step) ($Action)" | Add-Content $log; $np = Get-AppPid; if ($np) { $st.Pid = $np } }
    "$($st.Step),$Phase,$Action,$(Get-Date -Format o),$($s.Pss),$($s.Java),$($s.Native),$($s.Gfx),$($s.Threads),$($s.Fds),$($s.Cpu),$($s.Alive)" | Add-Content $csv
    $st.Rows.Add([pscustomobject]@{ Step = $st.Step; Phase = $Phase; Pss = $s.Pss; Threads = $s.Threads; Fds = $s.Fds; Cpu = $s.Cpu })
}

Send-Link 'frostline://debug/gc'
# Phase 1: baseline, 2 minutes (post-GC samples at 0, 60 and 120 s).
Measure-Step 1 'baseline-0'
Start-Sleep -Seconds 55; Measure-Step 1 'baseline-60'
Start-Sleep -Seconds 55; Measure-Step 1 'baseline-120'

# Phase 2: ten channel switches, 90 s apart.
for ($i = 0; $i -lt 10; $i++) {
    $ch = $Channels[$i % $Channels.Count]
    Send-Link "frostline://channel/$ch"
    Start-Sleep -Seconds 80
    Measure-Step 2 "switch-$ch"
}
# Phase 3: six chat hide/show cycles, 15 s apart.
for ($i = 0; $i -lt 6; $i++) {
    Send-Link 'frostline://debug/chat-toggle'; Start-Sleep -Seconds 4
    Send-Link 'frostline://debug/chat-toggle'; Start-Sleep -Seconds 4
    Measure-Step 3 "chat-toggle-$($i + 1)"
}
# Phase 4: four VOD round trips, 30 s each.
for ($i = 0; $i -lt 4; $i++) {
    Send-Link 'frostline://debug/open-vod'; Start-Sleep -Seconds 20
    Send-Link 'frostline://debug/back'; Start-Sleep -Seconds 4
    Measure-Step 4 "vod-$($i + 1)"
}
# Phase 5: three reconnects.
for ($i = 0; $i -lt 3; $i++) {
    Send-Link 'frostline://debug/reconnect'; Start-Sleep -Seconds 15
    Measure-Step 5 "reconnect-$($i + 1)"
}
# Phase 6: three token + integrity refreshes, 60 s apart.
for ($i = 0; $i -lt 3; $i++) {
    Send-Link 'frostline://debug/refresh-token'; Start-Sleep -Seconds 5
    Send-Link 'frostline://debug/refresh-integrity'; Start-Sleep -Seconds 45
    Measure-Step 6 "refresh-$($i + 1)"
}

# ---- summary ----
$crashRaw = Invoke-Tv @("logcat -d -b crash | grep -c $Package")
$crashBuf = [regex]::Match((Get-Section $crashRaw "logcat -d -b crash | grep -c $Package"), '\d+').Value
if (-not $crashBuf) { $crashBuf = '?' }
$dbgRaw = Invoke-Tv @("logcat -d -s FrostlineDebug:I | grep -c ' err'")
$dbgErr = [regex]::Match((Get-Section $dbgRaw "logcat -d -s FrostlineDebug:I | grep -c ' err'"), '\d+').Value
if (-not $dbgErr) { $dbgErr = '?' }

$rows = $st.Rows
$crashes = $st.Crashes
$appPid = $st.Pid
$v = Get-StressVerdict $rows -Crashes $crashes -PidChanged ($appPid -ne $startPid)
$pass = $v.Pass
$lines = @()
$lines += "Stress $Package on $TvHost, $($rows.Count) steps (post-GC values; PSS in MB)"
$lines += $v.Lines
$lines += "CPU mean $([math]::Round(($rows | Measure-Object Cpu -Average).Average, 1))%, max $(($rows | Measure-Object Cpu -Maximum).Maximum)%"
$lines += "Crashes / pid changes: $crashes (pid $startPid -> $appPid); crash buffer lines for the app: $crashBuf; debug actions that logged err: $dbgErr"
$lines += "VERDICT: $(if ($pass) { 'PASS' } else { 'FAIL' })  (each phase END post-GC at most +10 MB PSS and +5 threads/fds above the MAX of the baseline phase; peaks are reported, never fail a phase; PSS slope at most +0.5 MB/step; no crash)"
$lines += "Files: $OutDir (stress.csv, stress.log)"
$lines | Set-Content (Join-Path $OutDir 'summary.txt')
$lines

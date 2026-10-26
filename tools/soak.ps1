<#
.SYNOPSIS
  Long-run stability sampler for tv.frostline.app, safe to run detached for hours. Every -IntervalSec it records CPU, PSS
  (total, Java, native, graphics), thread count and fd count to soak.csv; at the end it prints (and writes to summary.txt)
  a summary of at most 15 lines with the slope per hour of PSS, threads and fds. Optionally switches channel every
  -SwitchMinutes with a frostline://channel/<login> deep link.

.DESCRIPTION
  Uses the same tv.py --batch client as metrics.ps1. tv.py errors are retried and a failed sample is skipped, never fatal.
  Only reads from the TV, plus the optional `am start` deep link. Start the app and a stream first.
  Some Android versions hide /proc/<pid>/fd from the shell user: the fd column is then -1.

.EXAMPLE
  .\tools\soak.ps1 -Hours 8
  Start-Process powershell -ArgumentList '-NoProfile','-File','tools\soak.ps1','-Hours','8' -WindowStyle Hidden
#>
param(
    [string]$TvHost = '192.168.68.54',
    [int]$Port = 5555,
    [double]$Hours = 4,
    [int]$IntervalSec = 60,
    [string]$Package = 'tv.frostline.app',
    [int]$SwitchMinutes = 30,
    [string[]]$Channels = @('xqc', 'shroud', 'pokimane', 'summit1g'),
    [string]$TvPy = 'C:\Users\karth\Documents\repos\cleanyourtv\tools\tv.py',
    [string]$Python = 'C:\Users\karth\Documents\repos\cleanyourtv\tools\venv\Scripts\python.exe',
    [string]$OutDir = (Join-Path $PSScriptRoot ("..\build\soak\" + (Get-Date -Format 'yyyyMMdd-HHmmss')))
)

$ErrorActionPreference = 'Continue'
if (-not (Test-Path $TvPy)) { throw "tv.py not found at $TvPy" }
if (-not (Test-Path $Python)) { throw "python not found at $Python" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$env:TV_HOST = $TvHost
$env:TV_PORT = "$Port"
. (Join-Path $PSScriptRoot 'tvlib.ps1')
. (Join-Path $PSScriptRoot 'stressverdict.ps1')

$log = Join-Path $OutDir 'soak.log'
$csv = Join-Path $OutDir 'soak.csv'
'sample,hours,time,cpu_pct,pss_mb,java_mb,native_mb,graphics_mb,threads,fds,channel,alive' | Set-Content $csv

$appPid = Get-AppPid
if (-not $appPid) { throw "$Package is not running on $TvHost. Start the app and a stream first." }
$total = [int][math]::Ceiling($Hours * 3600 / $IntervalSec)
$t0 = Get-Date
$nextSwitch = if ($SwitchMinutes -gt 0) { $t0.AddMinutes($SwitchMinutes) } else { [datetime]::MaxValue }
$chIdx = 0; $channel = ''; $crashes = 0; $failed = 0
$ts = New-Object System.Collections.Generic.List[double]
$pss = New-Object System.Collections.Generic.List[double]
$thr = New-Object System.Collections.Generic.List[double]
$fdl = New-Object System.Collections.Generic.List[double]
$cpus = New-Object System.Collections.Generic.List[double]

for ($i = 1; $i -le $total; $i++) {
    Start-Sleep -Seconds $IntervalSec
    try {
        if ((Get-Date) -ge $nextSwitch) {
            $channel = $Channels[$chIdx % $Channels.Count]; $chIdx++
            Invoke-Tv @("am start -a android.intent.action.VIEW -d frostline://channel/$channel $Package") | Out-Null
            $nextSwitch = (Get-Date).AddMinutes($SwitchMinutes)
            "$(Get-Date -Format o) switched to $channel" | Add-Content $log
            Start-Sleep -Seconds 20
        }
        $s = Get-Sample $appPid
        if (-not $s.Alive) {
            $newPid = Get-AppPid
            if ($newPid -and $newPid -ne $appPid) { $crashes++; $appPid = $newPid; "$(Get-Date -Format o) pid changed (restart or crash)" | Add-Content $log }
            else { $failed++; continue }
        } elseif ($s.Pid -ne $appPid) { $crashes++; $appPid = $s.Pid; "$(Get-Date -Format o) pid changed (restart or crash)" | Add-Content $log }
        if ($s.Pss -le 0) { $failed++; continue }
        $h = ((Get-Date) - $t0).TotalHours
        "$i,$([math]::Round($h, 3)),$(Get-Date -Format o),$($s.Cpu),$($s.Pss),$($s.Java),$($s.Native),$($s.Gfx),$($s.Threads),$($s.Fds),$channel,$($s.Alive)" | Add-Content $csv
        $ts.Add($h); $pss.Add($s.Pss); $thr.Add($s.Threads); $fdl.Add($s.Fds); $cpus.Add($s.Cpu)
    } catch {
        $failed++
        "$(Get-Date -Format o) sample $i failed: $($_.Exception.Message)" | Add-Content $log
    }
}

function Fmt([double]$v) { if ([double]::IsNaN($v)) { 'n/a' } else { '{0:N2}' -f $v } }
$lines = @()
$lines += "Soak $Package on ${TvHost}: $($ts.Count) samples over $([math]::Round(((Get-Date) - $t0).TotalHours, 2)) h ($failed failed), $chIdx channel switches"
if ($ts.Count -ge 3) {
    $lines += "PSS      first $($pss[0]) MB, last $($pss[$pss.Count - 1]) MB, max $(($pss | Measure-Object -Maximum).Maximum) MB, slope $(Fmt (Get-Slope $ts.ToArray() $pss.ToArray())) MB/h"
    $lines += "threads  first $($thr[0]), last $($thr[$thr.Count - 1]), slope $(Fmt (Get-Slope $ts.ToArray() $thr.ToArray()))/h"
    $lines += "fds      first $($fdl[0]), last $($fdl[$fdl.Count - 1]), slope $(Fmt (Get-Slope $ts.ToArray() $fdl.ToArray()))/h (-1 = hidden from shell)"
    $lines += (Format-CpuLine $cpus.ToArray())
}
$lines += "process restarts/crashes seen: $crashes"
$lines += "CSV: $csv"
$lines | Set-Content (Join-Path $OutDir 'summary.txt')
$lines

<#
.SYNOPSIS
  Long-run stability sampler for tv.frostline.app. Every -IntervalSec it records CPU, PSS (total, Java, native, graphics),
  thread count and fd count to a CSV, then prints the trend (slope per hour) for PSS, threads and fds.
  Optionally switches channel every -SwitchMinutes with a frostline://channel/<login> deep link.

.DESCRIPTION
  Uses the same tv.py --batch client as metrics.ps1 (no adb binary). It only reads from the TV, plus the optional
  `am start` deep link. Start the app and a stream first. Run it yourself; nothing in the build or the tests runs it.

  A flat line (slope near 0) after the first 30 minutes means no leak. PSS above the first-hour plateau that keeps
  climbing, or threads/fds that keep growing, point at a leak (listeners, sockets, jobs, caches).
  Note: some Android versions hide /proc/<pid>/fd from the shell user; the fd column is then -1. The player debug
  overlay's "uptime" line shows threads, fds and sockets from inside the app.

.EXAMPLE
  .\tools\soak.ps1 -Hours 8
  .\tools\soak.ps1 -Hours 4 -SwitchMinutes 30 -Channels xqc,shroud,pokimane
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

$ErrorActionPreference = 'Stop'
if (-not (Test-Path $TvPy)) { throw "tv.py not found at $TvPy" }
if (-not (Test-Path $Python)) { throw "python not found at $Python" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$env:TV_HOST = $TvHost
$env:TV_PORT = "$Port"

function Invoke-Tv([string[]]$Commands) {
    ($Commands -join "`n") | & $Python $TvPy --batch
}

function Get-Section([string]$Raw, [string]$Command) {
    $start = $Raw.IndexOf("`$ $Command")
    if ($start -lt 0) { return '' }
    $rest = $Raw.Substring($start + $Command.Length + 2)
    $next = [regex]::Match($rest, '(?m)^\$ ')
    if ($next.Success) { return $rest.Substring(0, $next.Index) }
    return $rest
}

function Get-Num([string]$Text, [string]$Pattern) {
    $m = [regex]::Match($Text, $Pattern)
    if ($m.Success) { return [double]$m.Groups[1].Value }
    return -1
}

# Least squares slope of y against t (hours).
function Get-Slope([double[]]$T, [double[]]$Y) {
    $n = $T.Count
    if ($n -lt 3) { return [double]::NaN }
    $mt = ($T | Measure-Object -Average).Average
    $my = ($Y | Measure-Object -Average).Average
    $num = 0.0; $den = 0.0
    for ($i = 0; $i -lt $n; $i++) { $num += ($T[$i] - $mt) * ($Y[$i] - $my); $den += ($T[$i] - $mt) * ($T[$i] - $mt) }
    if ($den -eq 0) { return [double]::NaN }
    return $num / $den
}

$pidRaw = (Invoke-Tv @("pidof $Package")) -join "`n"
$appPid = ([regex]::Match((Get-Section $pidRaw "pidof $Package"), '\d+')).Value
if (-not $appPid) { throw "$Package is not running on $TvHost. Start the app and a stream first." }
$total = [int][math]::Ceiling($Hours * 3600 / $IntervalSec)
$csv = Join-Path $OutDir 'soak.csv'
Write-Host "pid $appPid, $total samples every ${IntervalSec}s (about $Hours h) -> $csv"
'sample,hours,time,cpu_pct,pss_kb,java_kb,native_kb,graphics_kb,threads,fds,channel' | Set-Content $csv

$t0 = Get-Date
$nextSwitch = if ($SwitchMinutes -gt 0) { $t0.AddMinutes($SwitchMinutes) } else { [datetime]::MaxValue }
$chIdx = 0
$channel = ''
$ts = New-Object System.Collections.Generic.List[double]
$pss = New-Object System.Collections.Generic.List[double]
$thr = New-Object System.Collections.Generic.List[double]
$fdl = New-Object System.Collections.Generic.List[double]

for ($i = 1; $i -le $total; $i++) {
    Start-Sleep -Seconds $IntervalSec
    try {
        if ((Get-Date) -ge $nextSwitch) {
            $channel = $Channels[$chIdx % $Channels.Count]; $chIdx++
            Invoke-Tv @("am start -a android.intent.action.VIEW -d frostline://channel/$channel $Package") | Out-Null
            $nextSwitch = (Get-Date).AddMinutes($SwitchMinutes)
            Write-Host "switched to $channel"
            Start-Sleep -Seconds 20
            $pidRaw = (Invoke-Tv @("pidof $Package")) -join "`n"
            $appPid = ([regex]::Match((Get-Section $pidRaw "pidof $Package"), '\d+')).Value
            if (-not $appPid) { throw "app died after switching to $channel" }
        }
        $cmds = @("top -H -b -n 1 -p $appPid", "dumpsys meminfo $Package", "ls /proc/$appPid/task | wc -l", "ls /proc/$appPid/fd | wc -l")
        $raw = (Invoke-Tv $cmds) -join "`n"
        $top = Get-Section $raw $cmds[0]
        $mem = Get-Section $raw $cmds[1]
        $thrCount = Get-Num (Get-Section $raw $cmds[2]) '(\d+)'
        $fdText = Get-Section $raw $cmds[3]
        $fdCount = if ($fdText -match 'Permission|denied|No such') { -1 } else { Get-Num $fdText '(\d+)' }

        $pssKb = Get-Num $mem '(?m)TOTAL PSS:\s+(\d+)'
        if ($pssKb -lt 0) { $pssKb = Get-Num $mem '(?m)^\s*TOTAL\s+(\d+)' }
        $java = Get-Num $mem '(?m)Java Heap:\s+(\d+)'
        $nat = Get-Num $mem '(?m)Native Heap:\s+(\d+)'
        $gfx = Get-Num $mem '(?m)^\s*Graphics:\s+(\d+)'

        # Process CPU: the row of the main thread's process in `top -H` is per thread, so sum the CPU column of all rows.
        $cpu = 0.0
        foreach ($line in ($top -split "`n")) {
            $tok = $line.Trim() -split '\s+'
            for ($k = 0; $k -lt $tok.Count - 1; $k++) {
                if ($tok[$k] -match '^[RSDZTIt]$' -and $tok[$k + 1] -match '^\d+(\.\d+)?$' -and $tok[0] -match '^\d+$') { $cpu += [double]$tok[$k + 1]; break }
            }
        }
        $h = ((Get-Date) - $t0).TotalHours
        "$i,$([math]::Round($h, 3)),$(Get-Date -Format o),$cpu,$pssKb,$java,$nat,$gfx,$thrCount,$fdCount,$channel" | Add-Content $csv
        if ($pssKb -gt 0) { $ts.Add($h); $pss.Add($pssKb / 1024.0); $thr.Add($thrCount); $fdl.Add($fdCount) }
        Write-Host ("sample {0}/{1}: cpu {2:N0}%  pss {3:N0} MB  threads {4}  fds {5}" -f $i, $total, $cpu, ($pssKb / 1024.0), $thrCount, $fdCount)
    } catch {
        Write-Warning "sample $i failed: $($_.Exception.Message)"
    }
}

Write-Host ''
Write-Host "Trend over $([math]::Round(((Get-Date) - $t0).TotalHours, 2)) h ($($ts.Count) samples), slope per hour:"
$sp = Get-Slope $ts.ToArray() $pss.ToArray()
$st = Get-Slope $ts.ToArray() $thr.ToArray()
$sf = Get-Slope $ts.ToArray() $fdl.ToArray()
Write-Host ("  PSS     {0,8:N2} MB/h" -f $sp)
Write-Host ("  threads {0,8:N2} /h" -f $st)
Write-Host ("  fds     {0,8:N2} /h" -f $sf)
Write-Host "CSV: $csv"

<#
.SYNOPSIS
  Collects performance metrics for tv.frostline.app from an Android TV over adb, using the same
  adb_shell client as cleanyourtv\tools\tv.py (no adb binary needed).

.DESCRIPTION
  Per sample it records, as text files under -OutDir:
    top-<n>.txt       top -H -b -n 1 -p <pid>      per-thread CPU
    meminfo-<n>.txt   dumpsys meminfo tv.frostline.app
    gfxinfo-<n>.txt   dumpsys gfxinfo tv.frostline.app   (frame stats; reset before the first sample)
  plus summary.csv with total PSS (KB), janky frame percentage and the process CPU line.

  It only READS from the TV. Start playback on the TV first, then run this.
  Nothing here is run automatically by the build or the tests.

.EXAMPLE
  .\tools\metrics.ps1 -TvHost 192.168.x.y -Samples 12 -IntervalSec 5
#>
param(
    [string]$TvHost = '192.168.68.54',
    [int]$Port = 5555,
    [int]$Samples = 12,
    [int]$IntervalSec = 5,
    [string]$Package = 'tv.frostline.app',
    [string]$TvPy = 'C:\Users\karth\Documents\repos\cleanyourtv\tools\tv.py',
    [string]$Python = 'python',
    [string]$OutDir = (Join-Path $PSScriptRoot ("..\build\metrics\" + (Get-Date -Format 'yyyyMMdd-HHmmss')))
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path $TvPy)) { throw "tv.py not found at $TvPy" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$env:TV_HOST = $TvHost
$env:TV_PORT = "$Port"

function Invoke-Tv([string[]]$Commands) {
    # One connection, one command per line (tv.py --batch). Output is "$ cmd" followed by its output.
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

$pidRaw = (Invoke-Tv @("pidof $Package")) -join "`n"
$appPid = ([regex]::Match((Get-Section $pidRaw "pidof $Package"), '\d+')).Value
if (-not $appPid) { throw "$Package is not running on $TvHost. Start the app and a stream first." }
Write-Host "pid $appPid, $Samples samples every ${IntervalSec}s -> $OutDir"

# Frame stats start from zero so the janky percentage covers this run only.
Invoke-Tv @("dumpsys gfxinfo $Package reset") | Out-Null

"sample,time,pss_kb,janky_pct,total_frames,cpu_line" | Set-Content (Join-Path $OutDir 'summary.csv')
for ($i = 1; $i -le $Samples; $i++) {
    Start-Sleep -Seconds $IntervalSec
    $cmds = @("top -H -b -n 1 -p $appPid", "dumpsys meminfo $Package", "dumpsys gfxinfo $Package")
    $raw = (Invoke-Tv $cmds) -join "`n"
    $top = Get-Section $raw $cmds[0]
    $mem = Get-Section $raw $cmds[1]
    $gfx = Get-Section $raw $cmds[2]
    $n = '{0:D2}' -f $i
    $top | Set-Content (Join-Path $OutDir "top-$n.txt")
    $mem | Set-Content (Join-Path $OutDir "meminfo-$n.txt")
    $gfx | Set-Content (Join-Path $OutDir "gfxinfo-$n.txt")

    $pss = [regex]::Match($mem, '(?m)TOTAL PSS:\s+(\d+)|^\s*TOTAL\s+(\d+)')
    $pssKb = if ($pss.Groups[1].Success) { $pss.Groups[1].Value } else { $pss.Groups[2].Value }
    $janky = [regex]::Match($gfx, 'Janky frames:\s+(\d+)\s+\(([\d.]+)%\)').Groups[2].Value
    $frames = [regex]::Match($gfx, 'Total frames rendered:\s+(\d+)').Groups[1].Value
    $cpu = ([regex]::Match($top, '(?m)^\s*\d+%?cpu.*$|^.*%cpu.*$')).Value.Trim() -replace ',', ';'
    "$i,$(Get-Date -Format o),$pssKb,$janky,$frames,$cpu" | Add-Content (Join-Path $OutDir 'summary.csv')
    Write-Host "sample $i : PSS ${pssKb} KB, janky ${janky}%"
}
Write-Host "Done. See $OutDir"

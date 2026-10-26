# The stress verdict, separate from the TV so it can be tested (stressverdict.tests.ps1). Dot-source after tvlib.ps1 (needs Get-Slope).
#
# Rules:
#  - the baseline is the MAX of the baseline-phase (phase 1) samples (PSS, threads, fds), not the last one;
#  - a phase fails only if its END post-GC value is more than baseline + 10 MB PSS / +5 threads / +5 fds; peaks are reported, never fail a phase;
#  - the overall verdict also fails if the PSS slope over all steps is above +0.5 MB per step, or on a crash / pid change.
function Get-StressVerdict {
    param(
        [Parameter(Mandatory)] $Rows,          # objects with Step, Phase, Pss, Threads, Fds
        [int]$Crashes = 0,
        [bool]$PidChanged = $false,
        [double]$PssLimit = 10, [double]$ThreadLimit = 5, [double]$FdLimit = 5, [double]$SlopeLimit = 0.5
    )
    $names = @{ 1 = 'baseline'; 2 = 'channel switch'; 3 = 'chat toggle'; 4 = 'vod round trip'; 5 = 'reconnect'; 6 = 'token/integrity' }
    $baseRows = @($Rows | Where-Object { $_.Phase -eq 1 })
    $basePss = ($baseRows | Measure-Object Pss -Maximum).Maximum
    $baseThreads = ($baseRows | Measure-Object Threads -Maximum).Maximum
    $baseFds = ($baseRows | Measure-Object Fds -Maximum).Maximum
    $pass = $true
    $lines = @()
    $lines += ('{0,-16} {1,7} {2,7} {3,7} {4,9} {5,9}  {6}' -f 'phase', 'start', 'end', 'peak', 'd threads', 'd fds', 'verdict')
    foreach ($p in 1..6) {
        $r = @($Rows | Where-Object { $_.Phase -eq $p })
        if ($r.Count -eq 0) { continue }
        $first = $r[0]; $last = $r[$r.Count - 1]
        $peak = ($r | Measure-Object Pss -Maximum).Maximum
        $dt = $last.Threads - $baseThreads
        $fdOk = ($baseFds -lt 0 -or $last.Fds -lt 0)
        $df = if ($fdOk) { 'n/a' } else { $last.Fds - $baseFds }
        $ok = (($last.Pss - $basePss) -le $PssLimit) -and ($dt -le $ThreadLimit)
        if (-not $fdOk) { $ok = $ok -and (($last.Fds - $baseFds) -le $FdLimit) }
        if (-not $ok) { $pass = $false }
        $lines += ('{0,-16} {1,7} {2,7} {3,7} {4,9} {5,9}  {6}' -f $names[$p], $first.Pss, $last.Pss, $peak, $dt, $df, $(if ($ok) { 'ok' } else { 'FAIL' }))
    }
    $slope = Get-Slope ([double[]]($Rows | ForEach-Object { $_.Step })) ([double[]]($Rows | ForEach-Object { $_.Pss }))
    $slopeOk = [double]::IsNaN($slope) -or ($slope -le $SlopeLimit)
    if (-not $slopeOk) { $pass = $false }
    if ($Crashes -gt 0 -or $PidChanged) { $pass = $false }
    $lines += ('Post-GC PSS slope: {0:N2} MB per step ({1}); baseline (max of phase 1) {2} MB, final {3} MB' -f $slope, $(if ($slopeOk) { 'ok' } else { 'FAIL, limit +0.5' }), $basePss, @($Rows)[-1].Pss)
    [pscustomobject]@{ Pass = $pass; Slope = $slope; BasePss = $basePss; Lines = $lines }
}

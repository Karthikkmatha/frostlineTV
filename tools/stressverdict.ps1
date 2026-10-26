# The stress verdict, separate from the TV so it can be tested (stressverdict.tests.ps1). Dot-source after tvlib.ps1 (needs Get-Slope).
#
# Rules:
#  - the baseline is the MAX of the baseline-phase (phase 1) samples (PSS, java heap, threads, fds), not the last one;
#  - a phase fails only if its END post-GC value is more than baseline + 15 MB PSS (the Java heap swings, so 10 was too tight) / +5 threads / +5 fds; peaks are reported, never fail a phase;
#  - the overall verdict also fails if the PSS slope (or the Java heap slope) over all steps is above +0.5 MB per step (a leak), or on a crash / pid change;
#  - CPU samples above 400 % (more than four cores' worth) are parse errors and are ignored; CPU is reported as median and p90.

# Median / p90 of the usable CPU samples (0 < x <= 400). Returns an object with N (usable), Dropped, Median, P90 (NaN when there is none).
function Get-CpuStats {
    param($Samples, [double]$MaxValid = 400)
    $all = @($Samples | Where-Object { $null -ne $_ } | ForEach-Object { [double]$_ })
    $ok = @($all | Where-Object { $_ -ge 0 -and $_ -le $MaxValid } | Sort-Object)
    $pick = {
        param([double]$q)
        if ($ok.Count -eq 0) { return [double]::NaN }
        $pos = $q * ($ok.Count - 1)
        $lo = [math]::Floor($pos); $hi = [math]::Ceiling($pos)
        return [double]($ok[[int]$lo] + ($ok[[int]$hi] - $ok[[int]$lo]) * ($pos - $lo))
    }
    [pscustomobject]@{ N = $ok.Count; Dropped = ($all.Count - $ok.Count); Median = (& $pick 0.5); P90 = (& $pick 0.9) }
}

function Format-CpuLine {
    param($Samples)
    $c = Get-CpuStats $Samples
    if ($c.N -eq 0) { return 'CPU n/a (no usable samples)' }
    $med = ([double]$c.Median).ToString('N1')
    $p90 = ([double]$c.P90).ToString('N1')
    $note = ''
    if ($c.Dropped -gt 0) { $note = ", $($c.Dropped) sample(s) over 400 % ignored as parse errors" }
    return ('CPU median {0} %, p90 {1} % ({2} samples{3})' -f $med, $p90, $c.N, $note)
}

function Get-StressVerdict {
    param(
        [Parameter(Mandatory)] $Rows,          # objects with Step, Phase, Pss, Threads, Fds (Java optional)
        [int]$Crashes = 0,
        [bool]$PidChanged = $false,
        [double]$PssLimit = 15, [double]$ThreadLimit = 5, [double]$FdLimit = 5, [double]$SlopeLimit = 0.5
    )
    $names = @{ 1 = 'baseline'; 2 = 'channel switch'; 3 = 'chat toggle'; 4 = 'vod round trip'; 5 = 'reconnect'; 6 = 'token/integrity' }
    $baseRows = @($Rows | Where-Object { $_.Phase -eq 1 })
    $basePss = ($baseRows | Measure-Object Pss -Maximum).Maximum
    $baseThreads = ($baseRows | Measure-Object Threads -Maximum).Maximum
    $baseFds = ($baseRows | Measure-Object Fds -Maximum).Maximum
    $pass = $true
    $lines = @()
    $header = '{0,-16} {1,7} {2,7} {3,7} {4,9} {5,9}  {6}' -f 'phase', 'start', 'end', 'peak', 'd threads', 'd fds', 'verdict'
    $lines += $header
    foreach ($p in 1..6) {
        $r = @($Rows | Where-Object { $_.Phase -eq $p })
        if ($r.Count -eq 0) { continue }
        $first = $r[0]; $last = $r[$r.Count - 1]
        $peak = ($r | Measure-Object Pss -Maximum).Maximum
        $dt = $last.Threads - $baseThreads
        $fdHidden = ($baseFds -lt 0 -or $last.Fds -lt 0)
        $df = 'n/a'
        if (-not $fdHidden) { $df = [string]($last.Fds - $baseFds) }
        $ok = (($last.Pss - $basePss) -le $PssLimit) -and ($dt -le $ThreadLimit)
        if (-not $fdHidden) { $ok = $ok -and (($last.Fds - $baseFds) -le $FdLimit) }
        if (-not $ok) { $pass = $false }
        $word = 'ok'
        if (-not $ok) { $word = 'FAIL' }
        $lines += ('{0,-16} {1,7} {2,7} {3,7} {4,9} {5,9}  {6}' -f [string]$names[$p], [string]$first.Pss, [string]$last.Pss, [string]$peak, [string]$dt, $df, $word)
    }
    $steps = [double[]]@($Rows | ForEach-Object { [double]$_.Step })
    $slope = [double](Get-Slope $steps ([double[]]@($Rows | ForEach-Object { [double]$_.Pss })))
    $slopeOk = [double]::IsNaN($slope) -or ($slope -le $SlopeLimit)
    if (-not $slopeOk) { $pass = $false }
    if ($Crashes -gt 0 -or $PidChanged) { $pass = $false }
    $slopeWord = 'ok'
    if (-not $slopeOk) { $slopeWord = 'LEAK, limit +0.5' }
    $finalPss = @($Rows)[-1].Pss
    $slopeText = 'n/a'
    if (-not [double]::IsNaN($slope)) { $slopeText = $slope.ToString('N2') }
    $lines += ('Post-GC PSS slope: {0} MB per step ({1}); baseline (max of phase 1) {2} MB, final {3} MB' -f $slopeText, $slopeWord, [string]$basePss, [string]$finalPss)
    # The Java heap (post-GC) is reported the same way when the rows carry it: noisy, so only its slope can fail.
    $javaSlope = [double]::NaN
    $javaRows = @($Rows | Where-Object { $null -ne $_.PSObject.Properties['Java'] -and $null -ne $_.Java })
    if ($javaRows.Count -ge 3) {
        $javaSlope = [double](Get-Slope ([double[]]@($javaRows | ForEach-Object { [double]$_.Step })) ([double[]]@($javaRows | ForEach-Object { [double]$_.Java })))
        $jMax = ($javaRows | Where-Object { $_.Phase -eq 1 } | Measure-Object Java -Maximum).Maximum
        $jOk = [double]::IsNaN($javaSlope) -or ($javaSlope -le $SlopeLimit)
        if (-not $jOk) { $pass = $false }
        $jWord = 'ok'
        if (-not $jOk) { $jWord = 'LEAK, limit +0.5' }
        $jText = 'n/a'
        if (-not [double]::IsNaN($javaSlope)) { $jText = $javaSlope.ToString('N2') }
        $lines += ('Post-GC Java heap slope: {0} MB per step ({1}); baseline max {2} MB, final {3} MB' -f $jText, $jWord, [string]$jMax, [string]@($javaRows)[-1].Java)
    }
    [pscustomobject]@{ Pass = $pass; Slope = $slope; JavaSlope = $javaSlope; BasePss = $basePss; Lines = $lines }
}

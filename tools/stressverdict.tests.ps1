# Self-check of Get-StressVerdict. Run: powershell -NoProfile -ExecutionPolicy Bypass -File tools\stressverdict.tests.ps1   (exit code 1 on failure)
. (Join-Path $PSScriptRoot 'tvlib.ps1')
. (Join-Path $PSScriptRoot 'stressverdict.ps1')
$fail = 0
function Check([string]$name, $actual, $expected) { if ($actual -ne $expected) { Write-Host "FAIL $name : got $actual, expected $expected"; $script:fail++ } else { Write-Host "ok   $name" } }
function Rows($spec) { $i = 0; foreach ($s in $spec) { $i++; [pscustomobject]@{ Step = $i; Phase = $s[0]; Pss = $s[1]; Threads = 100; Fds = 50 } } }

# 1. baseline is the MAX of phase 1: a last baseline sample of 300 with an earlier 310 makes 318 acceptable (318 - 310 <= 10).
$v = Get-StressVerdict (Rows @(@(1,310),@(1,305),@(1,300),@(2,318),@(3,317),@(4,316),@(5,316),@(6,316))) -SlopeLimit 100
Check 'baseline is max of phase 1' $v.BasePss 310
Check 'end within +10 of max passes' $v.Pass $true

# 2. A phase fails only on its END value: a peak of 400 that comes back to 305 passes the phase (flat slope otherwise).
$v = Get-StressVerdict (Rows @(@(1,300),@(1,300),@(2,400),@(2,305),@(3,300),@(4,300),@(5,300),@(6,300))) -SlopeLimit 100
Check 'peak never fails a phase' $v.Pass $true

# 3. End more than +10 above the baseline fails.
$v = Get-StressVerdict (Rows @(@(1,300),@(1,300),@(2,312),@(3,300),@(4,300),@(5,300),@(6,300)))
Check 'end above baseline+10 fails' $v.Pass $false

# 4. Threads: end vs baseline +5.
$r = @(Rows @(@(1,300),@(1,300),@(2,300),@(3,300),@(4,300),@(5,300),@(6,300))); $r[4].Threads = 106
Check 'threads +6 fails' (Get-StressVerdict $r).Pass $false
$r[4].Threads = 105
Check 'threads +5 passes' (Get-StressVerdict $r).Pass $true

# 5. Slope above +0.5 MB per step fails even when every phase end is within +10 (slow creep: +0.6 per step over 14 steps = +8.4).
$spec = @(); for ($i = 0; $i -lt 14; $i++) { $ph = if ($i -lt 2) { 1 } else { 2 + [math]::Floor(($i - 2) / 3) }; $spec += ,@([int][math]::Min($ph, 6), (300 + 0.6 * $i)) }
$spec[0][0] = 1; $spec[1][0] = 1
$v = Get-StressVerdict (Rows $spec)
Check 'slope 0.6 fails' $v.Pass $false

# 6. A crash fails.
Check 'crash fails' (Get-StressVerdict (Rows @(@(1,300),@(1,300),@(2,300),@(3,300),@(4,300),@(5,300),@(6,300))) -Crashes 1).Pass $false
if ($fail -gt 0) { Write-Host "$fail failed"; exit 1 } else { Write-Host 'all passed'; exit 0 }

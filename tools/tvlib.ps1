# Shared helpers for soak.ps1 and stress.ps1 (dot-sourced). Talks to the TV only through tv.py --batch; read-only except `am start`.
# Requires $TvHost, $Port, $TvPy, $Python, $Package to be set by the caller.

function Invoke-Tv([string[]]$Commands, [int]$Tries = 4) {
    for ($a = 1; $a -le $Tries; $a++) {
        try {
            $out = ($Commands -join "`n") | & $Python $TvPy --batch 2>&1
            $text = ($out | Out-String)
            if ($LASTEXITCODE -eq 0 -and $text -notmatch 'Traceback|ConnectionRefused|timed out|Connection reset|ConnectionError') { return $text }
        } catch { }
        Start-Sleep -Seconds (5 * $a)
    }
    return ''
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

function Get-AppPid {
    $raw = Invoke-Tv @("pidof $Package")
    return ([regex]::Match((Get-Section $raw "pidof $Package"), '\d+')).Value
}

# Least squares slope of y against x.
function Get-Slope([double[]]$X, [double[]]$Y) {
    $n = $X.Count
    if ($n -lt 3) { return [double]::NaN }
    $mx = ($X | Measure-Object -Average).Average
    $my = ($Y | Measure-Object -Average).Average
    $num = 0.0; $den = 0.0
    for ($i = 0; $i -lt $n; $i++) { $num += ($X[$i] - $mx) * ($Y[$i] - $my); $den += ($X[$i] - $mx) * ($X[$i] - $mx) }
    if ($den -eq 0) { return [double]::NaN }
    return $num / $den
}

# threads/fds/sockets as the app itself counts them, for TVs where the shell user cannot read /proc/<pid>/fd (profileable, not
# debuggable, so run-as does not work either). frostline://debug/stats makes the app log
# "FrostlineDebug stats n=<seq> threads=.. fds=.. sockets=.." (needs Settings > Debug overlay ON). Returns $null when it did not log.
function Get-AppStats {
    Invoke-Tv @("am start -a android.intent.action.VIEW -d frostline://debug/stats $Package") | Out-Null
    Start-Sleep -Seconds 2
    $cmd = "logcat -d -s FrostlineDebug:I | grep ' stats n=' | tail -n 1"
    $text = Get-Section (Invoke-Tv @($cmd)) $cmd
    $m = [regex]::Match($text, 'stats n=(\d+) threads=(\d+) fds=(\d+) sockets=(\d+)')
    if (-not $m.Success) { return $null }
    [pscustomobject]@{ Seq = [int]$m.Groups[1].Value; Threads = [int]$m.Groups[2].Value; Fds = [int]$m.Groups[3].Value; Sockets = [int]$m.Groups[4].Value }
}

# One measurement: PSS (total/java/native/graphics, MB), threads, fds (-1 when hidden from the shell user), process CPU %, alive.
function Get-Sample([string]$AppPid) {
    $cmds = @("top -H -b -n 1 -p $AppPid", "dumpsys meminfo $Package", "ls /proc/$AppPid/task | wc -l", "ls /proc/$AppPid/fd | wc -l", "pidof $Package")
    $raw = Invoke-Tv $cmds
    $top = Get-Section $raw $cmds[0]
    $mem = Get-Section $raw $cmds[1]
    $fdText = Get-Section $raw $cmds[3]
    $pidNow = ([regex]::Match((Get-Section $raw $cmds[4]), '\d+')).Value
    $pss = Get-Num $mem '(?m)TOTAL PSS:\s+(\d+)'
    if ($pss -lt 0) { $pss = Get-Num $mem '(?m)^\s*TOTAL\s+(\d+)' }
    $cpu = 0.0
    foreach ($line in ($top -split "`n")) {
        $tok = $line.Trim() -split '\s+'
        for ($k = 0; $k -lt $tok.Count - 1; $k++) {
            if ($tok[$k] -match '^[RSDZTIt]$' -and $tok[$k + 1] -match '^\d+(\.\d+)?$' -and $tok[0] -match '^\d+$') { $cpu += [double]$tok[$k + 1]; break }
        }
    }
    $fds = $(if ($fdText -match 'Permission|denied|No such') { -1 } else { Get-Num $fdText '(\d+)' })
    $thr = Get-Num (Get-Section $raw $cmds[2]) '(\d+)'
    if ($fds -lt 0) { # hidden from the shell user: take the app's own count
        $st = Get-AppStats
        if ($st) { $fds = $st.Fds; if ($thr -lt 0) { $thr = $st.Threads } }
    }
    [pscustomobject]@{
        Pss = [math]::Round($pss / 1024.0, 1)
        Java = [math]::Round((Get-Num $mem '(?m)Java Heap:\s+(\d+)') / 1024.0, 1)
        Native = [math]::Round((Get-Num $mem '(?m)Native Heap:\s+(\d+)') / 1024.0, 1)
        Gfx = [math]::Round((Get-Num $mem '(?m)^\s*Graphics:\s+(\d+)') / 1024.0, 1)
        Threads = $thr
        Fds = $fds
        Cpu = $cpu
        Pid = $pidNow
        Alive = [bool]$pidNow
    }
}

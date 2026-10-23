param([int]$Every = 1, [int]$Last = 0, [string]$Log = "")
# Prints the per-update "fights since the last update" lines of the dev client's log as a compact table.
# Each cell: hits dealt / hits taken, crit share, sprint share, jumps a minute (share ending in a crit), reward per step.
if ($Log -eq "") { $Log = Join-Path $PSScriptRoot "client.log" }
$lines = Get-Content -LiteralPath $Log | Where-Object { $_ -match 'fights since the last update' }
function Cell([string]$s) {
    $s = $s.Trim()
    if ($s -match '^\S.*? (\d+) steps: (\d+) hits \((\d+)% crit, (\d+)% sprint\), (\d+) taken, (\d+) jumps/min of which (\d+)% crit, punish (\d+)/(\d+), rew (-?[\d.]+)') {
        return ('{0,3}/{1,-3} c{2,3}% s{3,3}% j{4,2}({5,3}%) p{6}/{7} r{8}' -f $Matches[2], $Matches[5], $Matches[3], $Matches[4], $Matches[6], $Matches[7], $Matches[8], $Matches[9], $Matches[10])
    }
    return '-'
}
$rows = New-Object System.Collections.Generic.List[string]
$i = 0
foreach ($l in $lines) {
    $i++
    if ($l -match '^\[(\d\d:\d\d:\d\d)\].*?\((\d+) steps in total\): own (.*?) \| traders (.*?) \| crit drill 1 (.*?) \| punish held (.*?) \| punish free (.*)$') {
        $time = $Matches[1]; $steps = [long]$Matches[2]
        $own = $Matches[3]; $tr = $Matches[4]; $d1 = $Matches[5]; $ph = $Matches[6]; $pf = $Matches[7]
        $rows.Add(('{0} {1,5:N0}k | own {2,-42} | trader {3,-42} | face {4,-42} | pun {5}' -f $time, ($steps / 1000), (Cell "x $own"), (Cell "x $tr"), (Cell "x $d1"), (Cell "x $ph")))
    }
}
"updates: $($rows.Count)   (cell = dealt/taken, crit share, sprint share, jumps per min (share ending in crit), punish taken/chances, reward per step)"
$sel = $rows
if ($Last -gt 0) { $sel = $rows | Select-Object -Last $Last }
$k = 0
foreach ($r in $sel) { $k++; if ($k % $Every -eq 0 -or $k -eq $sel.Count) { $r } }

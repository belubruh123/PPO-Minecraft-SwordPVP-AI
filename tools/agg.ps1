param([int]$Block = 20, [int]$Last = 0, [string]$Log = "", [switch]$Swings, [switch]$Crit)
# Sums the per-update "fights since the last update" lines over blocks of updates.
# Default view, per kind of fight: dealt/taken a minute, crit share, sprint share, jumps/min (share ending in a crit), reward per step, share of the steps;
#   slow / ord: the stage's own fights by the opponent's connection: dealt/taken a minute (ratio), crit share, reward per step.
# -Swings view, per kind of fight: swings a minute, share that landed, sweeps (share of hits), early swings, air swings a minute (share at nothing), hits answered.
if ($Log -eq "") { $Log = Join-Path $PSScriptRoot "client.log" }
$lines = Get-Content -LiteralPath $Log | Where-Object { $_ -match 'fights since the last update' }
$keys = @('st','h','c','s','t','j','jc','pt','pc','r','sw','early','clicks','air','airMiss','ans','toAns','two','three','over','noSwing','sat','sHeld','sTicks','cj','cc')
function Parse([string]$s) {
    if ($s.Trim() -match '(\d+) steps: (\d+) hits \((\d+)% crit, (\d+)% sprint\), (\d+) taken, (\d+) jumps/min of which (\d+)% crit, punish (\d+)/(\d+), rew (-?[\d.]+)(?:, sweeps (\d+), early (\d+), swings (\d+), air swings (\d+) \((\d+) at nothing\), hits answered (\d+)/(\d+)(?:, combos on it (\d+) of which a third hit (\d+)(?:, combos over (\d+) of which without a swing back (\d+)(?: \((\d+) of them three hits or more\), S held (\d+)/(\d+)(?:, chase jumps (\d+) of which a crit (\d+))?)?)?)?)?') {
        $st = [double]$Matches[1]; $h = [double]$Matches[2]
        $j = [double]$Matches[6] * $st / 1200.0
        $o = @{ st = $st; h = $h; c = $h * [double]$Matches[3] / 100; s = $h * [double]$Matches[4] / 100; t = [double]$Matches[5]; j = $j; jc = $j * [double]$Matches[7] / 100; pt = [double]$Matches[8]; pc = [double]$Matches[9]; r = $st * [double]$Matches[10]; sw = 0.0; early = 0.0; clicks = 0.0; air = 0.0; airMiss = 0.0; ans = 0.0; toAns = 0.0; two = 0.0; three = 0.0; over = 0.0; noSwing = 0.0; sat = 0.0; sHeld = 0.0; sTicks = 0.0; cj = 0.0; cc = 0.0 }
        if ($Matches[11] -ne $null -and $Matches[11] -ne '') { $o.sw = [double]$Matches[11]; $o.early = [double]$Matches[12]; $o.clicks = [double]$Matches[13]; $o.air = [double]$Matches[14]; $o.airMiss = [double]$Matches[15]; $o.ans = [double]$Matches[16]; $o.toAns = [double]$Matches[17] }
        if ($Matches[18] -ne $null -and $Matches[18] -ne '') { $o.two = [double]$Matches[18]; $o.three = [double]$Matches[19] }
        if ($Matches[20] -ne $null -and $Matches[20] -ne '') { $o.over = [double]$Matches[20]; $o.noSwing = [double]$Matches[21] }
        if ($Matches[22] -ne $null -and $Matches[22] -ne '') { $o.sat = [double]$Matches[22]; $o.sHeld = [double]$Matches[23]; $o.sTicks = [double]$Matches[24] }
        if ($Matches[25] -ne $null -and $Matches[25] -ne '') { $o.cj = [double]$Matches[25]; $o.cc = [double]$Matches[26] }
        return $o
    }
    return $null
}
function ParsePing([string]$s) {
    if ($s.Trim() -match '(\d+) steps: (\d+) dealt \((\d+)% crit, (\d+)% sprint\), (\d+) taken, rew (-?[\d.]+)') {
        $st = [double]$Matches[1]; $h = [double]$Matches[2]
        return @{ st = $st; h = $h; c = $h * [double]$Matches[3] / 100; s = $h * [double]$Matches[4] / 100; t = [double]$Matches[5]; j = 0.0; jc = 0.0; pt = 0.0; pc = 0.0; r = $st * [double]$Matches[6]; sw = 0.0; early = 0.0; clicks = 0.0; air = 0.0; airMiss = 0.0; ans = 0.0; toAns = 0.0; two = 0.0; three = 0.0; over = 0.0; noSwing = 0.0; sat = 0.0; sHeld = 0.0; sTicks = 0.0; cj = 0.0; cc = 0.0 }
    }
    return $null
}
function Zero() { $z = @{}; foreach ($k in $keys) { $z[$k] = 0.0 }; return $z }
function Add($a, $b) { if ($b -ne $null) { foreach ($k in $keys) { $a[$k] += $b[$k] } } }
function Show($a, [double]$all) {
    if ($a.st -le 0) { return '-' }
    $m = $a.st / 1200.0
    $cs = 0; $ss = 0; if ($a.h -gt 0) { $cs = 100 * $a.c / $a.h; $ss = 100 * $a.s / $a.h }
    $js = 0; if ($a.j -gt 0) { $js = 100 * $a.jc / $a.j }
    return ('{0,3:N0}/{1,-3:N0} c{2,3:N0}% s{3,3:N0}% j{4,2:N0}({5,2:N0}%) r{6,6:N3} [{7,2:N0}%]' -f ($a.h / $m), ($a.t / $m), $cs, $ss, ($a.j / $m), $js, ($a.r / $a.st), (100 * $a.st / $all))
}
function ShowCrit($a, [double]$all) {
    # dealt/taken a minute, crit share, jumps a minute (share ending in a crit), punish crits made/chances, air swings a minute (share at nothing), reward
    if ($a.st -le 0) { return '-' }
    $m = $a.st / 1200.0
    $cs = 0; if ($a.h -gt 0) { $cs = 100 * $a.c / $a.h }
    $js = 0; if ($a.j -gt 0) { $js = 100 * $a.jc / $a.j }
    $am = 0; if ($a.air -gt 0) { $am = 100 * $a.airMiss / $a.air }
    return ('{0,3:N0}/{1,-3:N0} c{2,3:N0}% j{3,3:N0}/m({4,3:N0}%) pun {5:N0}/{6:N0} chase {11:N0}/{12:N0} air{7,3:N0}/m({8,2:N0}% miss) r{9,6:N3} [{10,2:N0}%]' -f ($a.h / $m), ($a.t / $m), $cs, ($a.j / $m), $js, $a.pt, $a.pc, ($a.air / $m), $am, ($a.r / $a.st), (100 * $a.st / $all), $a.cc, $a.cj)
}
function ShowSwings($a) {
    if ($a.st -le 0) { return '-' }
    $m = $a.st / 1200.0
    $land = 0; if ($a.clicks -gt 0) { $land = 100 * $a.h / $a.clicks }
    $swp = 0; if ($a.h -gt 0) { $swp = 100 * $a.sw / $a.h }
    $am = 0; if ($a.air -gt 0) { $am = 100 * $a.airMiss / $a.air }
    $an = 0; if ($a.toAns -gt 0) { $an = 100 * $a.ans / $a.toAns }
    $sh = '  -'; if ($a.sTicks -gt 0) { $sh = ('{0,3:N0}%' -f (100 * $a.sHeld / $a.sTicks)) }
    $out2 = '  -'; if ($a.two -gt 0) { $out2 = ('{0,3:N0}%' -f (100 * (1 - $a.three / $a.two))) }
    return ('sw{0,3:N0}/m land{1,3:N0}% hit{2,3:N0}/tk{3,-3:N0} sweep{4,3:N0}% early{5,2:N0} air{6,3:N0}/m({7,2:N0}% miss) ans{8,3:N0}% combos{9,3:N0} outBy2 {10} noSwingBack {11:N0}/{12:N0} (3+hits: {13:N0}) S {14}' -f ($a.clicks / $m), $land, ($a.h / $m), ($a.t / $m), $swp, $a.early, ($a.air / $m), $am, $an, $a.two, $out2, $a.noSwing, $a.over, $a.sat, $sh)
}
function ShowPing($a) {
    if ($a.st -le 0) { return '-' }
    $m = $a.st / 1200.0
    $cs = 0; if ($a.h -gt 0) { $cs = 100 * $a.c / $a.h }
    $ratio = 0; if ($a.t -gt 0) { $ratio = $a.h / $a.t }
    return ('{0,3:N0}/{1,-3:N0} ({2,4:N2}) c{3,3:N0}% r{4,6:N3}' -f ($a.h / $m), ($a.t / $m), $ratio, $cs, ($a.r / $a.st))
}
$parsed = New-Object System.Collections.Generic.List[object]
foreach ($l in $lines) {
    if ($l -match '^\[(\d\d:\d\d:\d\d)\].*?\((\d+) steps in total\): own (.*?) \| traders (.*?) \| crit drill 1 (.*?) \| punish held (.*?) \| punish free (.*?)(?: \| slow connection (.*?) \| ordinary connection (.*?))?(?: \| combo drills (.*?))?(?: \| scripted opponents of earlier stages (.*?))?(?: \| runners (.*?) \| circlers (.*?))?(?: \| swings made by the answer rule (\d+))?$') {
        $parsed.Add(@{ time = $Matches[1]; steps = [long]$Matches[2]; own = (Parse $Matches[3]); tr = (Parse $Matches[4]); d1 = (Parse $Matches[5]); ph = (Parse $Matches[6]); pf = (Parse $Matches[7]); slow = (ParsePing ([string]$Matches[8])); ord = (ParsePing ([string]$Matches[9])); cd = (Parse ([string]$Matches[10])); reh = (Parse ([string]$Matches[11])); run = (Parse ([string]$Matches[12])); circ = (Parse ([string]$Matches[13])) })
    }
}
"updates: $($parsed.Count); blocks of $Block"
$out = New-Object System.Collections.Generic.List[string]
for ($i = 0; $i -lt $parsed.Count; $i += $Block) {
    $own = Zero; $tr = Zero; $d1 = Zero; $pu = Zero; $slow = Zero; $ord = Zero; $cd = Zero; $reh = Zero; $run = Zero; $circ = Zero
    $end = [Math]::Min($i + $Block, $parsed.Count) - 1
    for ($k = $i; $k -le $end; $k++) { Add $own $parsed[$k].own; Add $tr $parsed[$k].tr; Add $d1 $parsed[$k].d1; Add $pu $parsed[$k].ph; Add $pu $parsed[$k].pf; Add $slow $parsed[$k].slow; Add $ord $parsed[$k].ord; Add $cd $parsed[$k].cd; Add $reh $parsed[$k].reh; Add $run $parsed[$k].run; Add $circ $parsed[$k].circ }
    $all = $own.st + $tr.st + $d1.st + $pu.st + $run.st + $circ.st
    if ($all -le 0) { continue }
    if ($Crit) {
        $out.Add(('{0} {1,5:N0}k | own {2} | trader {3} | RUNNER {4} | CIRCLER {5} | face {6}' -f $parsed[$end].time, ($parsed[$end].steps / 1000), (Show $own $all), (Show $tr $all), (ShowCrit $run $all), (ShowCrit $circ $all), (Show $d1 $all)))
        continue
    }
    if ($Swings) {
        $out.Add(('{0} {1,5:N0}k | OWN {2} | TRADER {3} | COMBO DRILL {4} | SCRIPTED {5}' -f $parsed[$end].time, ($parsed[$end].steps / 1000), (ShowSwings $own), (ShowSwings $tr), (ShowSwings $cd), (ShowSwings $reh)))
    } else {
        $pp = '-'; if ($pu.pc -gt 0) { $pp = ('{0,3:N0}%' -f (100 * $pu.pt / $pu.pc)) }
        $out.Add(('{0} {1,5:N0}k | own {2,-44} | trader {3,-44} | face {4,-44} | pun {5,4} | SLOW {6,-32} | ORD {7,-32} | SCRIPTED {8}' -f $parsed[$end].time, ($parsed[$end].steps / 1000), (Show $own $all), (Show $tr $all), (Show $d1 $all), $pp, (ShowPing $slow), (ShowPing $ord), (ShowPing $reh)))
    }
}
if ($Last -gt 0) { $out | Select-Object -Last $Last } else { $out }

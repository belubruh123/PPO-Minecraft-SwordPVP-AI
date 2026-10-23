param([string]$Command = "", [int]$Last = 18, [int]$Wait = 3, [int]$Width = 900)
# Sends a /swordai command to the dev client through its inbox (if one is given) and prints what it said last.
$log = Join-Path $PSScriptRoot "client.log"
if ($Command -ne "") {
    Set-Content -Encoding ascii -LiteralPath "D:\swordai\run\swordai\inbox.txt" -Value $Command
    Start-Sleep -Seconds $Wait
}
"now " + (Get-Date -Format "HH:mm:ss")
$lines = Get-Content -LiteralPath $log
$bad = $lines | Where-Object { $_ -match 'error:|BUILD FAILED|Stopped because|internal error|\[.*ERROR\]' } | Select-Object -First 6
foreach ($b in $bad) { if ($b.Length -gt 400) { $b.Substring(0, 400) } else { $b } }
$mine = $lines | Where-Object { $_ -match '\(swordai\)' -or $_ -match '\[CHAT\] \[SwordAI\]' }
# The same line is logged twice (the logger and the chat); keep the chat copy when both are there.
$seen = @{}
$out = New-Object System.Collections.Generic.List[string]
foreach ($l in $mine) {
    $t = $l -replace '^\[(\d\d:\d\d:\d\d)\].*?(\(swordai\) |\[SwordAI\] )', '$1 '
    if (-not $seen.ContainsKey($t)) { $seen[$t] = 1; $out.Add($t) }
}
$out | Select-Object -Last $Last | ForEach-Object { if ($_.Length -gt $Width) { $_.Substring(0, $Width) + '...' } else { $_ } }

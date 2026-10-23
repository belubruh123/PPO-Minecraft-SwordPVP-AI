param([int]$Minutes = 10, [int]$Block = 40, [int]$Last = 3, [string]$Pattern = "", [string]$Grep = 'steps in total|self-play gen|copies before|by who moves first|duel against|next stage|S rule')
# Waits (or until a line matching -Pattern shows up in the log, or the client is gone), then prints where training stands.
$sp = $PSScriptRoot
$log = Join-Path $sp "client.log"
$until = (Get-Date).AddMinutes($Minutes)
$why = "time up"
while ((Get-Date) -lt $until) {
    Start-Sleep -Seconds 30
    if ($Pattern -ne "" -and (Select-String -LiteralPath $log -Pattern $Pattern -Quiet)) { $why = "FOUND: $Pattern"; break }
    $alive = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'fabric\.dli|net\.fabricmc' }
    if (-not $alive) { $why = "CLIENT GONE"; break }
    if (Select-String -LiteralPath $log -Pattern 'Stopped because|internal error' -Quiet) { $why = "STOPPED WITH AN ERROR"; break }
}
"$why at " + (Get-Date -Format 'HH:mm:ss')
Get-Content -LiteralPath $log | Select-String -Pattern 'CHAT\] \[SwordAI\] (It won|Stopped because|Self-play could not)' | Select-Object -Last 4 | ForEach-Object { $_.Line.Substring(0, [Math]::Min(300, $_.Line.Length)) }
& "$sp\agg.ps1" -Block $Block -Last $Last -Swings
& "$sp\agg.ps1" -Block $Block -Last $Last
& "$sp\say.ps1" -Command "swordai status" -Last 18 -Wait 6 -Width 1400 | Select-String -Pattern $Grep | ForEach-Object { $_.Line }

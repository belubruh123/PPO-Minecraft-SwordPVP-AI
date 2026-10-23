param([int]$Minutes = 15, [int]$Block = 40, [int]$Last = 5, [string]$Pattern = "Stage 9 learned|Now stage 10", [string]$Label = "STAGE 10 REACHED")
# Waits until the duel is passed (stage 10 begins), the client is gone, or the time is up; then prints where training stands.
$sp = $PSScriptRoot
$log = Join-Path $sp "client.log"
$until = (Get-Date).AddMinutes($Minutes)
$why = "time up"
while ((Get-Date) -lt $until) {
    Start-Sleep -Seconds 30
    if ($Pattern -ne "" -and (Select-String -LiteralPath $log -Pattern $Pattern -Quiet)) { $why = $Label; break }
    $alive = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'fabric\.dli|net\.fabricmc' }
    if (-not $alive) { $why = "CLIENT GONE"; break }
    if (Select-String -LiteralPath $log -Pattern 'Stopped because|internal error' -Quiet) { $why = "STOPPED WITH AN ERROR"; break }
}
"$why at " + (Get-Date -Format 'HH:mm:ss')
Get-Content -LiteralPath $log | Select-String -Pattern 'CHAT\] \[SwordAI\] (Stage|Now stage|The duel|Self-play|It takes|generation|The copy)' | Select-Object -Last 4 | ForEach-Object { $_.Line.Substring(0, [Math]::Min(300, $_.Line.Length)) }
& "$sp\agg.ps1" -Block $Block -Last $Last -Swings
& "$sp\agg.ps1" -Block $Block -Last $Last
& "$sp\say.ps1" -Command "swordai status" -Last 16 -Wait 6 | Select-String -Pattern 'steps in total|duel against|self-play|next stage|copies before|Stage \d' | ForEach-Object { $_.Line }

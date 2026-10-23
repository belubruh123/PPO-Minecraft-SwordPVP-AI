param([switch]$WhatIf)
# Installs the 0.14.0 jar and the trained network into the Modrinth profile, keeping everything it replaces.
$ErrorActionPreference = 'Stop'
$prof = 'C:\Users\jerem\AppData\Roaming\ModrinthApp\profiles\DRL AI'
$src = 'D:\swordai\run\swordai'
$jar = Get-ChildItem 'D:\swordai\build\libs' -Filter 'swordai-0.14.0*.jar' | Where-Object { $_.Name -notmatch 'sources|dev' } | Select-Object -First 1
if ($jar -eq $null) { throw 'no 0.14.0 jar in build\libs' }
$running = Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'DRL AI' }
if ($running) { throw 'the game is running in that profile' }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backup = Join-Path $prof "swordai\backup-before-0.14.0-$stamp"
"jar:    $($jar.FullName) ($($jar.Length) bytes)"
"backup: $backup"
if ($WhatIf) { 'what-if: nothing changed'; return }
New-Item -ItemType Directory -Force $backup | Out-Null
# What the trained state replaces.
foreach ($f in 'checkpoint.bin', 'curriculum.properties', 'metrics.csv', 'drill-copy.bin', 'champion.bin', 'crit-drills.txt') {
    $old = Join-Path $prof "swordai\$f"
    if (Test-Path -LiteralPath $old) { Copy-Item -LiteralPath $old -Destination $backup -Force }
}
Copy-Item -LiteralPath (Join-Path $prof 'swordai\config.json') -Destination $backup -Force
# The old jar goes to the backup, so that the game does not load two versions.
Get-ChildItem (Join-Path $prof 'mods') -Filter 'swordai-*.jar' | ForEach-Object { Move-Item -LiteralPath $_.FullName -Destination $backup -Force; "moved old jar: $($_.Name)" }
Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $prof 'mods') -Force
foreach ($f in 'checkpoint.bin', 'curriculum.properties', 'metrics.csv', 'drill-copy.bin', 'champion.bin', 'crit-drills.txt') {
    $new = Join-Path $src $f
    if (Test-Path -LiteralPath $new) { Copy-Item -LiteralPath $new -Destination (Join-Path $prof "swordai\$f") -Force; "installed: $f" }
}
'--- mods now'
Get-ChildItem (Join-Path $prof 'mods') | Select-Object Name, Length | Format-Table -AutoSize | Out-String
'--- backup'
Get-ChildItem $backup | Select-Object Name, Length | Format-Table -AutoSize | Out-String

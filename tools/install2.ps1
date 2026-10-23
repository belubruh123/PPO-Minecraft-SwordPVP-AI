param([switch]$WhatIf)
# Installs the 18 October build (menu, round recorder, spacing input) and the v18 network into the Modrinth
# profile. Everything it replaces is backed up to D:\swordai\profile-backups first (not on C).
$ErrorActionPreference = 'Stop'
$prof = 'C:\Users\jerem\AppData\Roaming\ModrinthApp\profiles\DRL AI'
$net = 'D:\swordai\run\kept-v18-1648'
$jar = Get-Item 'D:\swordai\build\libs\swordai-0.14.0+1.21.11.jar'
$running = Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'DRL AI' }
if ($running) { throw 'the game is running in that profile' }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backup = "D:\swordai\profile-backups\before-18oct-$stamp"
"jar:     $($jar.FullName) ($($jar.Length) bytes, $($jar.LastWriteTime))"
"network: $net"
"backup:  $backup"
if ($WhatIf) { 'what-if: nothing changed'; return }
New-Item -ItemType Directory -Force $backup | Out-Null
foreach ($f in 'checkpoint.bin', 'curriculum.properties', 'config.json', 'checkpoint-before-upgrade.bin') {
    $old = Join-Path $prof "swordai\$f"
    if (Test-Path -LiteralPath $old) { Copy-Item -LiteralPath $old -Destination $backup -Force }
}
Get-ChildItem (Join-Path $prof 'mods') -Filter 'swordai-*.jar' | ForEach-Object { Move-Item -LiteralPath $_.FullName -Destination $backup -Force; "moved old jar to the backup: $($_.Name)" }
Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $prof 'mods') -Force
Copy-Item -LiteralPath (Join-Path $net 'checkpoint.bin') -Destination (Join-Path $prof 'swordai\checkpoint.bin') -Force
Copy-Item -LiteralPath (Join-Path $net 'curriculum.properties') -Destination (Join-Path $prof 'swordai\curriculum.properties') -Force
Copy-Item -LiteralPath 'D:\swordai\README.md' -Destination (Join-Path $prof 'swordai\README-0.14.0.md') -Force
Copy-Item -LiteralPath 'D:\swordai\REQUIREMENTS.md' -Destination (Join-Path $prof 'swordai\REQUIREMENTS.md') -Force
# The old backup folder inside the profile goes to D as well.
$oldBackup = Join-Path $prof 'swordai\backup-before-0.14.0-20261014-235310'
if (Test-Path -LiteralPath $oldBackup) { Move-Item -LiteralPath $oldBackup -Destination 'D:\swordai\profile-backups\' -Force; 'moved the 14 October backup folder to D' }
'--- hashes'
$a = (Get-FileHash $jar.FullName).Hash; $b = (Get-FileHash (Join-Path $prof "mods\$($jar.Name)")).Hash
"jar  same: $($a -eq $b)"
$a = (Get-FileHash (Join-Path $net 'checkpoint.bin')).Hash; $b = (Get-FileHash (Join-Path $prof 'swordai\checkpoint.bin')).Hash
"net  same: $($a -eq $b)"
'--- mods now'
Get-ChildItem (Join-Path $prof 'mods') -Filter 'swordai*' | Select-Object Name, Length | Format-Table -AutoSize | Out-String
'--- backup'
Get-ChildItem $backup | Select-Object Name, Length | Format-Table -AutoSize | Out-String

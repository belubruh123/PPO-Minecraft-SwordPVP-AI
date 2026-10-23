param([Parameter(Mandatory)][string]$Net, [string]$Version = '0.15.0', [switch]$WhatIf)
# Installs a fresh build and the chosen network into the Modrinth profile. Everything it replaces is
# backed up to D:\swordai\profile-backups first (not on C).
$ErrorActionPreference = 'Stop'
$prof = 'C:\Users\jerem\AppData\Roaming\ModrinthApp\profiles\DRL AI'
$jar = Get-Item "D:\swordai\build\libs\swordai-$Version+1.21.11.jar"
if (-not (Test-Path -LiteralPath (Join-Path $Net 'checkpoint.bin'))) { throw "no checkpoint.bin in $Net" }
$running = Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'DRL AI' }
if ($running) { throw 'the game is running in that profile' }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backup = "D:\swordai\profile-backups\before-$Version-$stamp"
"jar:     $($jar.FullName) ($($jar.Length) bytes, $($jar.LastWriteTime))"
"network: $Net"
"backup:  $backup"
if ($WhatIf) { 'what-if: nothing changed'; return }
New-Item -ItemType Directory -Force $backup | Out-Null
foreach ($f in 'checkpoint.bin', 'curriculum.properties', 'config.json', 'checkpoint-before-upgrade.bin') {
    $old = Join-Path $prof "swordai\$f"
    if (Test-Path -LiteralPath $old) { Copy-Item -LiteralPath $old -Destination $backup -Force }
}
Get-ChildItem (Join-Path $prof 'mods') -Filter 'swordai-*.jar' | ForEach-Object { Move-Item -LiteralPath $_.FullName -Destination $backup -Force; "moved old jar to the backup: $($_.Name)" }
Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $prof 'mods') -Force
Copy-Item -LiteralPath (Join-Path $Net 'checkpoint.bin') -Destination (Join-Path $prof 'swordai\checkpoint.bin') -Force
Copy-Item -LiteralPath (Join-Path $Net 'curriculum.properties') -Destination (Join-Path $prof 'swordai\curriculum.properties') -Force
Get-ChildItem (Join-Path $prof 'swordai') -Filter 'README-*.md' | Where-Object { $_.Name -ne "README-$Version.md" } | ForEach-Object { Move-Item -LiteralPath $_.FullName -Destination $backup -Force }
Copy-Item -LiteralPath 'D:\swordai\README.md' -Destination (Join-Path $prof "swordai\README-$Version.md") -Force
Copy-Item -LiteralPath 'D:\swordai\REQUIREMENTS.md' -Destination (Join-Path $prof 'swordai\REQUIREMENTS.md') -Force
'--- hashes'
$a = (Get-FileHash $jar.FullName).Hash; $b = (Get-FileHash (Join-Path $prof "mods\$($jar.Name)")).Hash
"jar  same: $($a -eq $b)"
$a = (Get-FileHash (Join-Path $Net 'checkpoint.bin')).Hash; $b = (Get-FileHash (Join-Path $prof 'swordai\checkpoint.bin')).Hash
"net  same: $($a -eq $b)"
'--- mods now'
Get-ChildItem (Join-Path $prof 'mods') -Filter 'swordai*' | Select-Object Name, Length | Format-Table -AutoSize | Out-String
'--- backup'
Get-ChildItem $backup | Select-Object Name, Length | Format-Table -AutoSize | Out-String

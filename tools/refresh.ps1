# Stops training, makes the fighter as it is now the copy it fights (stage 10 begun again), and resumes.
$sp = $PSScriptRoot
& "$sp\say.ps1" -Command "swordai stop" -Last 1 -Wait 10 | Select-Object -Last 1
& "$sp\say.ps1" -Command "swordai stage 10" -Last 1 -Wait 5 | Out-Null
& "$sp\say.ps1" -Command "swordai train" -Last 1 -Wait 30 | Select-Object -Last 1 | ForEach-Object { $_.Substring(0, [Math]::Min(120, $_.Length)) }
Get-Item D:\swordai\run\swordai\champion.bin | ForEach-Object { "champion.bin " + $_.LastWriteTime.ToString('HH:mm:ss') }

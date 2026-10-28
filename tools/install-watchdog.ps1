# install-watchdog.ps1: registers the Windows task SwordAI-Watchdog (run it yourself, in PowerShell, once).
# Every 10 minutes it runs watchdog.sh outside the Claude app: if training's client log has gone stale for 10 minutes
# (as on 28 Oct, when an app update at 06:41 killed training for 12 hours) it restarts the client in school mode and the
# hourly check loop. It does nothing while D:\swordai\run\school-cycle.stop exists.
# Remove it with:  Unregister-ScheduledTask -TaskName SwordAI-Watchdog -Confirm:$false
$sp = 'C:\Users\jerem\AppData\Local\Temp\claude\D--swordai\a22e256c-5a71-40c7-b784-e1011c2e02b3\scratchpad\watchdog.sh'
$action = New-ScheduledTaskAction -Execute 'C:\Windows\System32\conhost.exe' -Argument ('--headless "C:\Program Files\Git\bin\bash.exe" -l "' + $sp + '"')
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(2) -RepetitionInterval (New-TimeSpan -Minutes 10) -RepetitionDuration (New-TimeSpan -Days 7)
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Minutes 30) -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive
Register-ScheduledTask -TaskName 'SwordAI-Watchdog' -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Description 'Restarts SwordAI school training if the client log goes stale.'

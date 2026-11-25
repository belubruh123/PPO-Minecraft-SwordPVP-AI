# training-window.ps1 [hide|show]: takes the training client's window off the taskbar and moves it off-screen (hide), or
# brings it back (show). It stays a normal visible window either way, so the game renders and trains at full speed.
# school-restart.sh runs "hide" after every start. To look at the game: powershell -File D:\swordai\tools\training-window.ps1 show
param([string]$Mode = 'hide')
Add-Type -Name Win -Namespace SwordAI -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool ShowWindow(System.IntPtr h, int c);
[DllImport("user32.dll")] public static extern int GetWindowLong(System.IntPtr h, int i);
[DllImport("user32.dll")] public static extern int SetWindowLong(System.IntPtr h, int i, int v);
[DllImport("user32.dll")] public static extern bool SetWindowPos(System.IntPtr h, System.IntPtr a, int x, int y, int cx, int cy, uint f);
'@
$p = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'fabric\.dli\.' } | Select-Object -First 1
if (-not $p) { 'no training client running'; exit 1 }
$h = (Get-Process -Id $p.ProcessId).MainWindowHandle
if ($h -eq [IntPtr]::Zero) { 'training client has no window yet'; exit 1 }
$TOOLWINDOW = 0x80; $APPWINDOW = 0x40000; $ex = [SwordAI.Win]::GetWindowLong($h, -20)
if ($Mode -eq 'show') { $ex = ($ex -band (-bnot $TOOLWINDOW)) -bor $APPWINDOW; $x = 100; $show = 5 }
else { $ex = ($ex -bor $TOOLWINDOW) -band (-bnot $APPWINDOW); $x = -5000; $show = 4 }
[SwordAI.Win]::ShowWindow($h, 0) | Out-Null                       # the taskbar only notices the style change across a hide/show
[SwordAI.Win]::SetWindowLong($h, -20, $ex) | Out-Null
[SwordAI.Win]::SetWindowPos($h, [IntPtr]::Zero, $x, 100, 0, 0, 0x15) | Out-Null   # NOSIZE|NOZORDER|NOACTIVATE
[SwordAI.Win]::ShowWindow($h, $show) | Out-Null
"training window: $Mode"

@echo off
set JAVA_HOME=C:\Users\jerem\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2
set JAVA_TOOL_OPTIONS=-Xmx2G
call D:\swordai\gradlew.bat -p D:\swordai runClient -PcarpetPvp=D:/swordai/external/carpet-pvp/carpet-pvp-1.21.11-19.jar "--args=--quickPlaySingleplayer devworld --width 640 --height 360" --console=plain > "C:\Users\jerem\AppData\Local\Temp\claude\D--swordai\a22e256c-5a71-40c7-b784-e1011c2e02b3\scratchpad\client.log" 2>&1
echo exit code %ERRORLEVEL% >> "C:\Users\jerem\AppData\Local\Temp\claude\D--swordai\a22e256c-5a71-40c7-b784-e1011c2e02b3\scratchpad\client.log"

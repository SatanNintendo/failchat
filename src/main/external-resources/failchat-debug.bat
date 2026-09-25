@echo off
setlocal
cd /d "%~dp0"

if not exist "%~dp0runtime\bin\java.exe" (
    echo Failchat runtime was not found:
    echo "%~dp0runtime\bin\java.exe"
    pause
    exit /b 1
)

"%~dp0runtime\bin\java.exe" -Xmx200m -Xms100m -XX:+UseG1GC -javaagent:"%~dp0java-agents\transparent-webview-patch.jar" -jar "%~dp0failchat-${project.version}.jar"

echo.
echo Failchat exited with code %ERRORLEVEL%.
pause

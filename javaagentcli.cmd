@echo off
REM JavaAgent CLI launcher for Windows
setlocal
set "SCRIPT_DIR=%~dp0"
java --enable-native-access=ALL-UNNAMED -jar "%SCRIPT_DIR%target\javaagent-cli-1.0.0.jar" %*

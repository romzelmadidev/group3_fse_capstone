@echo off
set ADB="%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist %ADB% set ADB=adb

echo Bridging backend ports to connected Android device...
%ADB% reverse tcp:8080 tcp:8080
%ADB% reverse tcp:8081 tcp:8081
%ADB% reverse tcp:8082 tcp:8082
%ADB% reverse tcp:8083 tcp:8083
%ADB% reverse tcp:8084 tcp:8084
%ADB% reverse tcp:8085 tcp:8085
%ADB% reverse tcp:8086 tcp:8086
%ADB% reverse tcp:8025 tcp:8025
%ADB% reverse tcp:10005 tcp:10005

echo.
echo Active tunnels:
%ADB% reverse --list
echo.
echo Done! Ports bridged successfully.

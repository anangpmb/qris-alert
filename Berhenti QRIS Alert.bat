@echo off
setlocal
cd /d "%~dp0"

set "NODE=node"
if exist "%~dp0runtime\node.exe" set "NODE=%~dp0runtime\node.exe"

"%NODE%" server\server.js --stop
timeout /t 3 /nobreak >nul

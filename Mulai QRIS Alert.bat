@echo off
setlocal
cd /d "%~dp0"

rem Pakai node.exe portabel di folder "runtime" kalau ada, selain itu Node.js yang terpasang
set "NODE=node"
if exist "%~dp0runtime\node.exe" set "NODE=%~dp0runtime\node.exe"

if /i "%~1"=="_jalan" goto jalan

"%NODE%" --version >nul 2>&1
if errorlevel 1 goto tanpanode

rem Jalankan server di jendela yang diperkecil, supaya tidak mengganggu saat streaming
start "QRIS Alert" /min cmd /c ""%~f0" _jalan %~1"
exit /b 0

:jalan
title QRIS Alert - biarkan jendela ini terbuka selama streaming
set "BUKA=--open"
if /i "%~2"=="tanpabrowser" set "BUKA="
"%NODE%" server\server.js %BUKA%
if errorlevel 1 pause
exit /b

:tanpanode
echo.
echo   Node.js belum terpasang di komputer ini.
echo   Halaman unduhan Node.js akan dibuka. Pasang versi LTS,
echo   lalu klik dua kali file ini lagi.
echo.
start "" "https://nodejs.org/id/download"
pause
exit /b 1

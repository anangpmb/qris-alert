@echo off
setlocal

rem Klik sekali untuk mengaktifkan, klik lagi untuk mematikan

set "STARTUP=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup"
for /f "usebackq delims=" %%i in (`powershell -NoProfile -Command "[Environment]::GetFolderPath('Startup')"`) do set "STARTUP=%%i"

set "LINK=%STARTUP%\QRIS Alert.lnk"
set "TARGET=%~dp0Mulai QRIS Alert.bat"
set "WORKDIR=%~dp0"

if exist "%LINK%" goto matikan

powershell -NoProfile -ExecutionPolicy Bypass -Command "$s = (New-Object -ComObject WScript.Shell).CreateShortcut($env:LINK); $s.TargetPath = $env:TARGET; $s.Arguments = 'tanpabrowser'; $s.WorkingDirectory = $env:WORKDIR; $s.WindowStyle = 7; $s.Save()"
if not exist "%LINK%" goto gagal

echo.
echo   Autostart aktif.
echo   QRIS Alert akan menyala sendiri setiap kali kamu masuk ke Windows,
echo   tanpa membuka dashboard. Klik file ini lagi untuk mematikannya.
echo.
echo   Kalau folder QRIS Alert dipindah, matikan lalu aktifkan lagi autostart.
echo.
pause
exit /b 0

:matikan
del "%LINK%"
echo.
echo   Autostart dimatikan. Klik file ini lagi untuk mengaktifkannya kembali.
echo.
pause
exit /b 0

:gagal
echo.
echo   Autostart gagal dibuat.
echo   Pastikan PowerShell tidak diblokir di komputer ini, lalu coba lagi.
echo.
pause
exit /b 1

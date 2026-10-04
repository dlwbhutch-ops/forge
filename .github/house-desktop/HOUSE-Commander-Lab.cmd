@echo off
setlocal
cd /d "%~dp0"

set "HOUSE_JAVA=%~dp0runtime\bin\javaw.exe"
if exist "%HOUSE_JAVA%" goto run

set "HOUSE_JAVA=%~dp0runtime\bin\java.exe"
if exist "%HOUSE_JAVA%" goto run

where javaw >nul 2>&1
if %errorlevel%==0 (
  set "HOUSE_JAVA=javaw"
  goto run
)

where java >nul 2>&1
if %errorlevel%==0 (
  set "HOUSE_JAVA=java"
  goto run
)

echo HOUSE Commander Lab could not find its bundled Java runtime.
echo Re-extract the complete HOUSE package and try again.
pause
exit /b 1

:run
"%HOUSE_JAVA%" -Xmx4096m -Dfile.encoding=UTF-8 --add-opens=java.desktop/java.beans=ALL-UNNAMED --add-opens=java.desktop/javax.swing.border=ALL-UNNAMED --add-opens=java.desktop/javax.swing.event=ALL-UNNAMED --add-opens=java.desktop/sun.swing=ALL-UNNAMED --add-opens=java.desktop/java.awt.image=ALL-UNNAMED --add-opens=java.desktop/java.awt.color=ALL-UNNAMED --add-opens=java.desktop/sun.awt.image=ALL-UNNAMED --add-opens=java.desktop/javax.swing=ALL-UNNAMED --add-opens=java.desktop/java.awt=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.text=ALL-UNNAMED --add-opens=java.desktop/java.awt.font=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/java.math=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED --add-opens=java.base/java.net=ALL-UNNAMED -cp "%~dp0HOUSE-Commander-Lab.jar" com.housecommander.desktop.HouseDesktopMain
endlocal

@echo off
setlocal
cd /d "%~dp0"
rem Switch the console to UTF-8 so non-English restaurant names (Tamil, Hindi, ...) display correctly.
chcp 65001 >nul
if exist "target\restaurant-finder-1.0.0-all.jar" (
    java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar "target\restaurant-finder-1.0.0-all.jar"
) else (
    mvn compile exec:java
)
pause

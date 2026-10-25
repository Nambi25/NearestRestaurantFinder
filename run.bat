@echo off
setlocal
cd /d "%~dp0"
if exist "target\restaurant-finder-1.0.0-all.jar" (
    java -jar "target\restaurant-finder-1.0.0-all.jar"
) else (
    mvn compile exec:java
)
pause

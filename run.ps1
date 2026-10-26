$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $scriptDir

# Switch the console to UTF-8 so non-English restaurant names (Tamil, Hindi, ...) display correctly.
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$jar = Join-Path $scriptDir "target\restaurant-finder-1.0.0-all.jar"

if (Test-Path $jar) {
    java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar $jar
} else {
    mvn compile exec:java
}

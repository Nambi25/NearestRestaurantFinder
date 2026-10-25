$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $scriptDir

$jar = Join-Path $scriptDir "target\restaurant-finder-1.0.0-all.jar"

if (Test-Path $jar) {
    java -jar $jar
} else {
    mvn compile exec:java
}

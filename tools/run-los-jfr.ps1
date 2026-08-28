param(
    [string] $ServerRoot = 'C:\Users\laoba\Documents\摸鱼生存服务端',
    [Parameter(Mandatory = $true)]
    [string] $JavaHome,
    [ValidateSet('on', 'off')]
    [string] $SectionLookupReuse = 'on',
    [ValidateRange(1, 65535)]
    [int] $RconPort = 25575,
    [Parameter(Mandatory = $true)]
    [string] $RconPassword,
    [ValidateRange(1, 65535)]
    [int] $ServerPort = 25565,
    [ValidateRange(1, 100)]
    [int] $Rounds = 3,
    [ValidateRange(0, 100)]
    [int] $WarmupRounds = 1,
    [ValidateRange(1, 3600)]
    [int] $Duration = 60,
    [ValidateRange(1, 100000)]
    [int] $EntityCount = 1000,
    [string] $World = 'minecraft:overworld',
    [string] $MobType = 'minecraft:villager',
    [string] $TargetType = 'minecraft:zombie',
    [string] $JarName = 'lattice-paperclip-1.21.11-R0.1-SNAPSHOT-mojmap.jar',
    [ValidatePattern('^[0-9]+[MG]$')]
    [string] $HeapSize = '8G',
    [switch] $SparkProfile,
    [int] $SparkIntervalMillis = 4,
    [string] $RecordingPath = ''
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $ServerRoot -PathType Container)) {
    throw "ServerRoot does not exist: $ServerRoot"
}
$java = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
    throw "Java executable does not exist: $java"
}
$runner = Join-Path $PSScriptRoot 'run-entity-jfr.ps1'
if (-not (Test-Path -LiteralPath $runner -PathType Leaf)) {
    throw "Entity JFR runner does not exist: $runner"
}
$jar = Join-Path $ServerRoot $JarName
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw "Server jar does not exist: $jar"
}

# The delegated runner starts the server, enables RCON, removes its benchmark
# entities before each round, and removes them again in its finally block.  We
# only pass a JVM property, so lattice.yml and user files are never rewritten.
$recording = if ([string]::IsNullOrWhiteSpace($RecordingPath)) {
    Join-Path $ServerRoot ("debug\los-{0}-{1}.jfr" -f $SectionLookupReuse, (Get-Date -Format 'yyyyMMdd-HHmmss'))
} else {
    $RecordingPath
}
$reuseValue = if ($SectionLookupReuse -eq 'on') { 'true' } else { 'false' }
$jvmOptions = @("-Dlattice.nativeLosSectionLookupReuse=$reuseValue")
$runnerArgs = @(
    '-ServerRoot', $ServerRoot,
    '-JavaHome', $JavaHome,
    '-RecordingPath', $recording,
    '-RconPassword', $RconPassword,
    '-RconPort', $RconPort,
    '-ServerPort', $ServerPort,
    '-Rounds', $Rounds,
    '-WarmupRounds', $WarmupRounds,
    '-MobAmount', $EntityCount,
    '-MobType', $MobType,
    '-TargetAmount', 1,
    '-TargetType', $TargetType,
    '-JfrSeconds', $Duration,
    '-BotWorld', $World,
    '-ItembenchWorld', $World,
    '-JarName', $JarName,
    '-HeapSize', $HeapSize,
    '-SparkIntervalMillis', $SparkIntervalMillis,
    '-JvmOptions', $jvmOptions
)
if ($SparkProfile) { $runnerArgs += '-SparkProfile' }

Write-Host ("LOS run: section-lookup-reuse={0}, entities={1}, rounds={2}, duration={3}s" -f
    $SectionLookupReuse, $EntityCount, $Rounds, $Duration)
Write-Host "Cleanup: run-entity-jfr removes only its benchmark tag/type through RCON; no unrelated process is stopped."
& $runner @runnerArgs
if ($LASTEXITCODE -ne 0) {
    throw "LOS JFR runner failed with exit code $LASTEXITCODE"
}

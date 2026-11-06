param(
    [ValidateSet('masks', 'arithmetic', 'lifecycle')][string]$Mode = 'masks',
    [string]$Sizes = '32,64,128,256',
    [ValidateRange(1, 100)][int]$Rounds = 1,
    [ValidateRange(1, 1000)][int]$Warmup = 5,
    [ValidateRange(1, 1000)][int]$Samples = 11,
    [ValidateRange(1, 100000)][int]$Iterations = 32,
    [string]$OutputDirectory,
    [switch]$IncludeKernel
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
if (-not $OutputDirectory) { throw 'OutputDirectory is required' }
$sizeValues = @($Sizes.Split(',') | ForEach-Object { [int]::Parse($_.Trim()) })
if ($sizeValues.Count -eq 0 -or @($sizeValues | Where-Object { $_ -le 0 }).Count -ne 0 -or
    @($sizeValues | Select-Object -Unique).Count -ne $sizeValues.Count) { throw 'Sizes must be distinct positive integers' }
$entries = @(if ($IncludeKernel) { 'snapshot'; 'kernel' } else { 'snapshot' })
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $outputRoot) { throw "Output already exists: $outputRoot" }
New-Item -ItemType Directory -Path $outputRoot | Out-Null
$env:JAVA_HOME = 'C:\Program Files\Dragonwell\dragonwell-21.0.12.0.12+8-GA'
Push-Location -LiteralPath $repo
try {
    $head = @(& git.exe rev-parse HEAD)
    if ($LASTEXITCODE -ne 0) { throw 'git rev-parse failed' }
    $diff = @(& git.exe diff -- lattice-native lattice-server/src/main)
    if ($LASTEXITCODE -ne 0) { throw 'git diff failed' }
    $sha = [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes(($diff -join "`n")))
    $libraryPaths = @(
        (Join-Path $repo 'build/lattice-native/lattice.dll'),
        (Join-Path $repo 'lattice-server/build/resources/main/META-INF/native/windows-x86_64/lattice.dll')
    )
    $librariesBefore = @($libraryPaths | ForEach-Object {
        if (-not (Test-Path -LiteralPath $_ -PathType Leaf)) { throw "Missing native library: $_" }
        @{ path = $_; sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash }
    })
    if ($librariesBefore[0].sha256 -ne $librariesBefore[1].sha256) {
        throw 'Build and resource native libraries differ'
    }
    $manifest = [ordered]@{
        head = $head[0]; sourceDiffSha256 = [Convert]::ToHexString($sha)
        javaHome = $env:JAVA_HOME; mode = $Mode; sizes = $Sizes; rounds = $Rounds
        warmup = $Warmup; samples = $Samples; iterations = $Iterations
        entries = $entries; worlds = @('serpentine', 'village')
        lengths = @('short', 'long'); reverse = @('false', 'true')
        startedUtc = [DateTime]::UtcNow.ToString('o')
        nativeLibrariesBefore = $librariesBefore
    }
    foreach ($entry in $entries) {
        foreach ($length in @('short', 'long')) {
            foreach ($reverse in @('false', 'true')) {
                $arguments = @(':lattice-server:pathfinderOptimizationBenchmark',
                    "-PpathfinderAuditMode=$Mode", "-PpathfinderAuditSizes=$Sizes", "-PpathfinderAuditRounds=$Rounds",
                    "-PpathfinderSearchBenchmarkEntry=$entry", '-PpathfinderSearchBenchmarkWorld=both',
                    "-PpathfinderSearchBenchmarkLength=$length", "-PpathfinderSearchBenchmarkReverse=$reverse",
                    "-PpathfinderSearchBenchmarkWarmup=$Warmup", "-PpathfinderSearchBenchmarkSamples=$Samples",
                    "-PpathfinderSearchBenchmarkIterations=$Iterations", '--console=plain')
                $log = Join-Path $outputRoot "$entry-$length-$reverse.log"
                "COMMAND JAVA_HOME=$env:JAVA_HOME gradlew.bat $($arguments -join ' ')" | Tee-Object -FilePath $log
                & .\gradlew.bat @arguments 2>&1 | Tee-Object -FilePath $log -Append
                $exitCode = $LASTEXITCODE
                "EXIT=$exitCode" | Tee-Object -FilePath $log -Append
                if ($exitCode -ne 0) { throw "Benchmark failed: $log (exit $exitCode)" }
            }
        }
    }
    $manifest.nativeLibrariesAfter = @($libraryPaths | ForEach-Object {
        @{ path = $_; sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash }
    })
    for ($i = 0; $i -lt $libraryPaths.Count; $i++) {
        if ($librariesBefore[$i].sha256 -ne $manifest.nativeLibrariesAfter[$i].sha256) {
            throw "Native library changed during measurements: $($libraryPaths[$i])"
        }
    }
    $manifest.finishedUtc = [DateTime]::UtcNow.ToString('o')
    $manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $outputRoot 'manifest.json') -Encoding utf8
} finally {
    Pop-Location
}

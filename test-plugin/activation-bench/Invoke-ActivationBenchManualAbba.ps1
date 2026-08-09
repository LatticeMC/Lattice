[CmdletBinding()]
param(
    [ValidateSet(1, 2, 4, 8, 16)]
    [int]$Bots = 4,

    [ValidateSet('overlap', 'disjoint')]
    [string]$Layout = 'overlap',

    [ValidateRange(1, 100000)]
    [int]$EntityCount = 100000,

    [ValidateRange(5, 100)]
    [int]$Pairs = 5,

    [ValidateRange(0, 20)]
    [int]$WarmupPairs = 1,

    [ValidateRange(1, 3600)]
    [int]$MeasureSeconds = 60,

    [ValidateNotNullOrEmpty()]
    [string]$World = 'world',

    [ValidateNotNullOrEmpty()]
    [string]$HostName = '127.0.0.1',

    [ValidateRange(1, 65535)]
    [int]$Port = 25565,

    [ValidateNotNullOrEmpty()]
    [string]$BotPrefix = 'LatticeActBot',

    [string]$RepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path,

    [string]$OutputDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($BotPrefix -notmatch '^[A-Za-z0-9_]+$' -or ($BotPrefix + $Bots).Length -gt 16) {
    throw 'BotPrefix must use ASCII letters, digits, underscores, and leave room for the bot suffix.'
}

$repo = Resolve-Path -LiteralPath $RepoRoot
$gradle = Join-Path $repo 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) {
    throw "gradlew.bat was not found under $repo"
}

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $OutputDirectory = Join-Path $PSScriptRoot "results\activation-abba-$stamp"
}
if (Test-Path -LiteralPath $OutputDirectory) {
    throw "OutputDirectory already exists: $OutputDirectory"
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

$metadata = [ordered]@{
    schema = 1
    createdAt = [DateTimeOffset]::UtcNow.ToString('O')
    machine = $env:COMPUTERNAME
    user = $env:USERNAME
    operatingSystem = (Get-CimInstance -ClassName Win32_OperatingSystem).Caption
    processor = (Get-CimInstance -ClassName Win32_Processor | Select-Object -First 1 -ExpandProperty Name)
    powershell = $PSVersionTable.PSVersion.ToString()
    repository = $repo.Path
    host = $HostName
    port = $Port
    world = $World
    bots = $Bots
    botPrefix = $BotPrefix
    layout = $Layout
    entityCount = $EntityCount
    measureSeconds = $MeasureSeconds
    warmupPairs = $WarmupPairs
    measuredPairs = $Pairs
    schedule = 'ABBA per pair; A=KD-tree disabled, B=KD-tree enabled'
    requirement = 'Every trial is a separate cold server JVM. This script deliberately does not start, stop, or inject commands into the server.'
}
$metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'metadata.json') -Encoding utf8

$trials = [System.Collections.Generic.List[object]]::new()
$pairPlan = @('A', 'B', 'B', 'A')
for ($warmup = 1; $warmup -le $WarmupPairs; $warmup++) {
    foreach ($side in $pairPlan) {
        $trials.Add([pscustomobject]@{ Phase = 'warmup'; Pair = $warmup; Side = $side })
    }
}
for ($pair = 1; $pair -le $Pairs; $pair++) {
    foreach ($side in $pairPlan) {
        $trials.Add([pscustomobject]@{ Phase = 'measure'; Pair = $pair; Side = $side })
    }
}

$samples = [System.Collections.Generic.List[object]]::new()
for ($index = 0; $index -lt $trials.Count; $index++) {
    $trial = $trials[$index]
    $enabled = $trial.Side -eq 'B'
    $flag = "-Dlattice.entityActivationKdTree=$($enabled.ToString().ToLowerInvariant())"
    $prefixFlag = if ($BotPrefix -eq 'LatticeActBot') { '' } else { " -Dlattice.activationBenchBotPrefix=$BotPrefix" }
    $trialName = '{0:D3}-{1}-pair{2:D2}-{3}' -f ($index + 1), $trial.Phase, $trial.Pair, $trial.Side
    $botResult = Join-Path $OutputDirectory "$trialName-bots.json"
    $holdSeconds = $MeasureSeconds + 30
    $botArgs = "--host $HostName --port $Port --bots $Bots --prefix $BotPrefix --hold-seconds $holdSeconds --output $botResult"
    $gradleCommand = ".\gradlew.bat :test-plugin:runActivationBench --no-daemon --args=`"$botArgs`""
    $prepareCommand = "/activationbench prepare $EntityCount $Layout $Bots $World"

    Write-Host ''
    Write-Host "=== $trialName ($($index + 1)/$($trials.Count)) ===" -ForegroundColor Cyan
    Write-Host "1. Cold-start the server with $flag$prefixFlag and online-mode=false."
    Write-Host "2. Run from the repository root: $prepareCommand"
    Write-Host '3. Wait for `/activationbench status` to report phase=prepared.'
    Write-Host "4. In a separate terminal run: $gradleCommand"
    Write-Host "5. Verify `/activationbench status` reports botsOnline=$Bots, then run `/activationbench start`."
    Write-Host "6. Measure exactly $MeasureSeconds seconds, capture TPS/MSPT, then run `/activationbench stop`."
    Write-Host '7. Wait for the bot runner to exit and write its JSON result.'
    Read-Host 'Press Enter only after all seven steps have completed' | Out-Null

    $botSuccess = $false
    $botError = $null
    if (Test-Path -LiteralPath $botResult -PathType Leaf) {
        try {
            $botJson = Get-Content -LiteralPath $botResult -Raw | ConvertFrom-Json
            $botSuccess = [bool]$botJson.success
            if (-not $botSuccess) {
                $botError = 'bot runner recorded success=false'
            }
        } catch {
            $botError = "cannot parse bot JSON: $($_.Exception.Message)"
        }
    } else {
        $botError = "bot JSON missing: $botResult"
    }

    $tps = [double](Read-Host 'Observed TPS mean (numeric)')
    $mspt = [double](Read-Host 'Observed MSPT mean (numeric)')
    $samples.Add([pscustomobject]@{
        trial = $index + 1
        phase = $trial.Phase
        pair = $trial.Pair
        side = $trial.Side
        kdTreeEnabled = $enabled
        tpsMean = $tps
        msptMean = $mspt
        botResult = $botResult
        botSuccess = $botSuccess
        botError = $botError
        completedAt = [DateTimeOffset]::UtcNow.ToString('O')
    })
    $samples | Export-Csv -LiteralPath (Join-Path $OutputDirectory 'samples.csv') -NoTypeInformation -Encoding utf8
}

$measured = @($samples | Where-Object { $_.phase -eq 'measure' -and $_.botSuccess })
$sideA = @($measured | Where-Object { -not $_.kdTreeEnabled })
$sideB = @($measured | Where-Object { $_.kdTreeEnabled })
$mean = {
    param([object[]]$Values, [string]$Property)
    if ($Values.Count -eq 0) {
        return $null
    }
    return [double](($Values | Measure-Object -Property $Property -Average).Average)
}
$summary = [ordered]@{
    measuredTrials = $measured.Count
    sideA = [ordered]@{
        count = $sideA.Count
        tpsMean = & $mean $sideA 'tpsMean'
        msptMean = & $mean $sideA 'msptMean'
    }
    sideB = [ordered]@{
        count = $sideB.Count
        tpsMean = & $mean $sideB 'tpsMean'
        msptMean = & $mean $sideB 'msptMean'
    }
}
$summary | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'summary.json') -Encoding utf8
Write-Host "Raw samples and metadata written to $OutputDirectory" -ForegroundColor Green

param(
    [Parameter(Mandatory = $true)]
    [string] $ServerRoot,

    [Parameter(Mandatory = $true)]
    [string] $JavaHome,

    [Parameter(Mandatory = $true)]
    [string] $RecordingPath,

    [Parameter(Mandatory = $true)]
    [string] $RconPassword,

    [ValidateRange(1, 65535)]
    [int] $RconPort = 25575,

    [ValidateRange(1, 65535)]
    [int] $ServerPort = 25565,

    [int] $Rounds = 3,
    [int] $WarmupRounds = 1,
    [int] $MobAmount = 1000,
    [string] $MobType = "minecraft:villager",
    [int] $TargetAmount = 2000,
    [string] $TargetType = "minecraft:zombie",
    [int] $JfrSeconds = 60,
    [int] $SettleSeconds = 8,
    [string] $BotName = "LatticeBench",
    [int] $BotX = 0,
    [int] $BotY = 64,
    [int] $BotZ = 0,
    [string] $BotWorld = "minecraft:overworld",
    [string] $ItembenchWorld = "overworld",
    [int] $SpawnRadius = 12,
    [int] $MoveRadius = 8,
    [int] $MoveIntervalMillis = 500,
    [ValidateSet("entity", "pathfinder", "itembench")]
    [string] $BenchmarkMode = "entity",
    [int] $ItemAmount = 100000,
    [int] $ItemPerTick = 1000,
    [ValidateSet("compact", "spread", "hopper-single", "hopper-array", "piston-array")]
    [string] $ItemLayout = "compact",
    [int] $PathMobAmount = 128,
    [int] $PathRequestsPerTick = 16,
    [int] $PathTargetRadius = 32,
    [string] $PathBenchWorld = "world",
    [string] $AdditionalPluginJar = "",
    [string] $TargetTag = "LatticePathTarget",
    [int] $ForceLoadRadius = 32,
    [string] $JarName = "lattice-paperclip-1.21.11-R0.1-SNAPSHOT-mojmap.jar",
    [ValidatePattern('^[0-9]+[MG]$')]
    [string] $HeapSize = "8G",
    [bool] $EnableJfr = $true,
    [switch] $SparkProfile,
    [int] $SparkIntervalMillis = 4,
    [string[]] $JvmOptions = @()
)

# Entity-tick JFR harness. Entity mode uses villagers panicking around a moving
# zombie for a natural Brain/goal/pathfinding load. Pathfinder mode loads the
# test plugin and issues an exact number of synchronous Paper Pathfinder calls.

$ErrorActionPreference = "Stop"
$properties = Join-Path $ServerRoot "server.properties"
$backup = Join-Path $env:TEMP ("lattice-entity-properties-{0}.backup" -f [Guid]::NewGuid())
$debugDirectory = Join-Path $ServerRoot "debug"
New-Item -ItemType Directory -Path $debugDirectory -Force | Out-Null
$stdout = Join-Path $debugDirectory "entity-jfr.out.log"
$stderr = Join-Path $debugDirectory "entity-jfr.err.log"

function Read-Exact([System.IO.Stream] $Stream, [int] $Count) {
    $buffer = [byte[]]::new($Count)
    $offset = 0
    while ($offset -lt $Count) {
        $read = $Stream.Read($buffer, $offset, $Count - $offset)
        if ($read -le 0) { throw "RCON connection closed" }
        $offset += $read
    }
    return $buffer
}

function Send-Rcon([System.Net.Sockets.TcpClient] $Client, [int] $Id, [int] $Type, [string] $Command) {
    $body = [System.IO.MemoryStream]::new()
    $writer = [System.IO.BinaryWriter]::new($body)
    $writer.Write($Id)
    $writer.Write($Type)
    $writer.Write([Text.Encoding]::UTF8.GetBytes($Command + [char] 0 + [char] 0))

    $packet = $body.ToArray()
    $frame = [System.IO.MemoryStream]::new()
    $frameWriter = [System.IO.BinaryWriter]::new($frame)
    $frameWriter.Write([int] $packet.Length)
    $frameWriter.Write($packet)
    $bytes = $frame.ToArray()
    $stream = $Client.GetStream()
    $stream.Write($bytes, 0, $bytes.Length)
    $stream.Flush()

    $length = [BitConverter]::ToInt32((Read-Exact $stream 4), 0)
    $response = Read-Exact $stream $length
    return [pscustomobject]@{
        Id = [BitConverter]::ToInt32($response, 0)
        Text = [Text.Encoding]::UTF8.GetString($response, 8, $response.Length - 10)
    }
}

function Open-Rcon {
    $client = [System.Net.Sockets.TcpClient]::new("127.0.0.1", $RconPort)
    $auth = Send-Rcon $client 1 3 $RconPassword
    if ($auth.Id -ne 1) {
        $client.Dispose()
        throw "RCON authentication failed"
    }
    return $client
}

function Get-RoundRecordingPath([int] $Round) {
    if ($Rounds -eq 1) { return $RecordingPath }
    $directory = Split-Path -Parent $RecordingPath
    if (-not $directory) { $directory = "." }
    $name = [IO.Path]::GetFileNameWithoutExtension($RecordingPath)
    $extension = [IO.Path]::GetExtension($RecordingPath)
    return Join-Path $directory ("{0}-{1:D2}{2}" -f $name, $Round, $extension)
}

function Remove-ColorCodes([string] $Text) {
    if ($null -eq $Text) { return "" }
    return $Text -replace ([string][char]0x00A7 + "."), ""
}

function Invoke-RconCommand([string] $Command, [ref] $CommandId) {
    $client = Open-Rcon
    try {
        $response = Send-Rcon $client $CommandId.Value 2 $Command
        $CommandId.Value++
        return $response.Text
    } finally {
        $client.Dispose()
    }
}

# spark uploads the profile asynchronously, so the RCON reply to "profiler stop"
# usually comes back before the link exists. The link is always echoed to the
# console log, so poll that instead and report whatever URL is new since the
# marker we captured before stopping.
function Get-SparkUrls([string] $LogPath) {
    if (-not (Test-Path -LiteralPath $LogPath -PathType Leaf)) { return @() }
    $stream = $null
    $reader = $null
    try {
        $stream = [IO.FileStream]::new($LogPath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
        $reader = [IO.StreamReader]::new($stream)
        $text = $reader.ReadToEnd()
    } finally {
        if ($reader) {
            $reader.Dispose()
        } elseif ($stream) {
            $stream.Dispose()
        }
    }
    return @([regex]::Matches($text, "https://spark\.lucko\.me/\S+") | ForEach-Object { $_.Value })
}

function Wait-SparkUrl([string] $LogPath, [int] $KnownCount, [int] $TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $urls = @(Get-SparkUrls $LogPath)
        if ($urls.Count -gt $KnownCount) { return $urls[$urls.Count - 1] }
        Start-Sleep -Seconds 2
    }
    return $null
}

function Invoke-MovingTargetLoad([int] $Seconds, [ref] $CommandId) {
    if ($MoveRadius -lt 1) { throw "MoveRadius must be at least 1" }
    if ($MoveIntervalMillis -lt 100) { throw "MoveIntervalMillis must be at least 100" }

    $positions = @(
        [pscustomobject]@{ X = $BotX + $MoveRadius; Z = $BotZ },
        [pscustomobject]@{ X = $BotX; Z = $BotZ + $MoveRadius },
        [pscustomobject]@{ X = $BotX - $MoveRadius; Z = $BotZ },
        [pscustomobject]@{ X = $BotX; Z = $BotZ - $MoveRadius }
    )
    $deadline = (Get-Date).AddSeconds($Seconds)
    $step = 0
    $client = Open-Rcon
    try {
        while ((Get-Date) -lt $deadline) {
            $position = $positions[$step % $positions.Count]
            $command = "execute in {0} run tp @e[tag={1},limit=1] {2} {3} {4}" -f `
                $BotWorld, $TargetTag, $position.X, $BotY, $position.Z
            $null = Send-Rcon $client $CommandId.Value 2 $command
            $CommandId.Value++
            $step++
            Start-Sleep -Milliseconds $MoveIntervalMillis
        }
    } finally {
        $client.Dispose()
    }
}

function Invoke-BenchmarkLoad([int] $Seconds, [ref] $CommandId) {
    if ($BenchmarkMode -in @("pathfinder", "itembench")) {
        Start-Sleep -Seconds $Seconds
    } else {
        Invoke-MovingTargetLoad $Seconds $CommandId
    }
}

function Invoke-Itembench([string] $Action, [ref] $CommandId) {
    return Invoke-RconCommand $Action $CommandId
}

function Wait-ItembenchCleanup([ref] $CommandId) {
    $deadline = (Get-Date).AddMinutes(2)
    while ((Get-Date) -lt $deadline) {
        $status = Invoke-Itembench "itembench status" $CommandId
        if ($status -match "cleaning=(?:false|False)\b" -or $status -match "remaining=0\b") { return }
        Start-Sleep -Seconds 1
    }
    Write-Warning "itembench cleanup did not finish within 120 seconds; the runner will stop the server and treat shutdown as the cleanup boundary"
}

function Wait-ItembenchMeasuring([ref] $CommandId) {
    $deadline = (Get-Date).AddMinutes(5)
    $hopperLayout = $ItemLayout -in @("hopper-single", "hopper-array")
    $measureRequested = $false
    while ((Get-Date) -lt $deadline) {
        $status = Invoke-Itembench "itembench status" $CommandId
        $atTarget = $status -match ("spawned={0}\b" -f $ItemAmount)
        if ($hopperLayout -and $status -match "phase=ready\b" -and $atTarget -and $status -match ("live={0}\b" -f $ItemAmount)) {
            if ($measureRequested) {
                throw "itembench remained ready after a successful measure request: $status"
            }
            $measureResponse = Invoke-Itembench "itembench measure" $CommandId
            if ($measureResponse -notmatch ("^Itembench measuring: target={0}\b" -f $ItemAmount)) {
                throw "itembench plugin did not enter measuring phase: $measureResponse"
            }
            $measureRequested = $true
            continue
        }
        if ($status -match "phase=measuring\b" -and $atTarget) {
            if ($hopperLayout -or $status -match ("live={0}\b" -f $ItemAmount)) {
                return $status
            }
        }
        if ($status -match "phase=(?:cleaning|stopped)\b") {
            throw "itembench left the spawn or ready phase before measurement began: $status"
        }
        Start-Sleep -Seconds 1
    }
    throw "itembench did not reach a stable $ItemAmount-item measuring phase within 5 minutes"
}

function Move-ExistingArtifactAside([string] $Path, [string] $DestinationDirectory) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return }
    New-Item -ItemType Directory -Path $DestinationDirectory -Force | Out-Null
    $destination = Join-Path $DestinationDirectory ([IO.Path]::GetFileName($Path))
    if (Test-Path -LiteralPath $destination) {
        $destination = Join-Path $DestinationDirectory ("{0}.{1}" -f [IO.Path]::GetFileName($Path), [Guid]::NewGuid())
    }
    Move-Item -LiteralPath $Path -Destination $destination
}

Copy-Item -LiteralPath $properties -Destination $backup -Force
$lines = Get-Content -LiteralPath $properties
$updates = @{
    "broadcast-rcon-to-ops" = "false"
    "enable-rcon" = "true"
    "rcon.password" = $RconPassword
    "rcon.port" = [string] $RconPort
    "server-port" = [string] $ServerPort
    "server-ip" = "127.0.0.1"
}
for ($i = 0; $i -lt $lines.Count; $i++) {
    $separator = $lines[$i].IndexOf('=')
    if ($separator -gt 0) {
        $key = $lines[$i].Substring(0, $separator)
        if ($updates.ContainsKey($key)) {
            $lines[$i] = $key + "=" + $updates[$key]
            $updates.Remove($key)
        }
    }
}
foreach ($entry in $updates.GetEnumerator()) {
    $lines += $entry.Key + "=" + $entry.Value
}
[IO.File]::WriteAllLines($properties, $lines, [Text.UTF8Encoding]::new($false))

$process = $null
$forceLoaded = $false
$pathbenchRunning = $false
$artifactBackupDirectory = Join-Path $env:TEMP ("lattice-entity-artifacts-{0}" -f [Guid]::NewGuid())
$forceLoadMinX = $BotX - $ForceLoadRadius
$forceLoadMinZ = $BotZ - $ForceLoadRadius
$forceLoadMaxX = $BotX + $ForceLoadRadius
$forceLoadMaxZ = $BotZ + $ForceLoadRadius
$forceLoadCommand = "execute in {0} run forceload add {1} {2} {3} {4}" -f `
    $BotWorld, $forceLoadMinX, $forceLoadMinZ, $forceLoadMaxX, $forceLoadMaxZ
$forceUnloadCommand = "execute in {0} run forceload remove {1} {2} {3} {4}" -f `
    $BotWorld, $forceLoadMinX, $forceLoadMinZ, $forceLoadMaxX, $forceLoadMaxZ
try {
    if ($Rounds -lt 1) { throw "Rounds must be at least 1" }
    if ($RconPort -eq $ServerPort) { throw "RconPort and ServerPort must be different" }
    if ($WarmupRounds -lt 0) { throw "WarmupRounds cannot be negative" }
    if ($MobAmount -lt 1 -or $TargetAmount -lt 0) { throw "MobAmount must be at least 1 and TargetAmount cannot be negative" }
    if ($BenchmarkMode -eq "itembench" -and ($ItemAmount -lt 1 -or $ItemAmount -gt 100000 -or $ItemPerTick -lt 1)) {
        throw "ItemAmount must be 1..100000 and ItemPerTick must be at least 1"
    }

    Move-ExistingArtifactAside $stdout $artifactBackupDirectory
    Move-ExistingArtifactAside $stderr $artifactBackupDirectory
    for ($round = 1; $round -le $Rounds; $round++) {
        Move-ExistingArtifactAside (Get-RoundRecordingPath $round) $artifactBackupDirectory
    }

    if ($BenchmarkMode -in @("pathfinder", "itembench") -and -not (Test-Path -LiteralPath $AdditionalPluginJar -PathType Leaf)) {
        throw "AdditionalPluginJar is required for $BenchmarkMode mode: $AdditionalPluginJar"
    }
    $serverArguments = if ($BenchmarkMode -in @("pathfinder", "itembench")) {
        @("-add-plugin=$AdditionalPluginJar", "--nogui")
    } else {
        @("--nogui")
    }
    $javaArguments = @($JvmOptions) + @("-Xms$HeapSize", "-Xmx$HeapSize", "-XX:+UseG1GC", "-jar", (Join-Path $ServerRoot $JarName)) + $serverArguments
    $process = Start-Process -FilePath (Join-Path $JavaHome "bin\java.exe") `
        -ArgumentList $javaArguments `
        -WorkingDirectory $ServerRoot -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru -WindowStyle Hidden

    $deadline = (Get-Date).AddMinutes(3)
    while ((Get-Date) -lt $deadline) {
        if ((Test-Path $stdout) -and ((Get-Content $stdout -Raw) -match "Done \(")) { break }
        if ($process.HasExited) { throw "Server exited during startup" }
        Start-Sleep -Seconds 2
    }
    if (-not ((Get-Content $stdout -Raw) -match "Done \(")) { throw "Server did not become ready" }

    $commandId = 2
    Write-Output ("forceload: " + (Invoke-RconCommand $forceLoadCommand ([ref] $commandId)))
    $forceLoaded = $true

    $client = Open-Rcon
    try {
        $null = Send-Rcon $client $commandId 2 ("kill @e[tag={0}]" -f $TargetTag)
        $commandId++
        if ($BenchmarkMode -eq "entity") {
            # Villagers remain active on the benchmark server even without a
            # real player. A nearby moving zombie drives panic and walk-target
            # recalculation, producing a repeatable natural pathfinding load.
            for ($targetIndex = 0; $targetIndex -lt $TargetAmount; $targetIndex++) {
                $targetX = $BotX + ($targetIndex % 32)
                $targetZ = $BotZ + [int]($targetIndex / 32)
                $spawnTarget = 'execute in {0} run summon {1} {2} {3} {4} {{Tags:["{5}"],NoAI:1b,Invulnerable:1b,PersistenceRequired:1b}}' -f `
                    $BotWorld, $TargetType, $targetX, $BotY, $targetZ, $TargetTag
                $null = Send-Rcon $client $commandId 2 $spawnTarget
                $commandId++
            }
            Write-Output ("target spawn: {0} {1}" -f $TargetAmount, $TargetType)
        }
        # Keep zombies alive and hostile: normal difficulty (peaceful despawns
        # hostiles), fixed midnight so they don't burn in daylight, and freeze
        # the clock so the burn condition never returns mid-recording.
        foreach ($cmd in @("difficulty normal", "time set midnight", "gamerule doDaylightCycle false", "weather clear", "gamerule doWeatherCycle false", "gamerule doMobSpawning false", "gamerule maxEntityCramming 0")) {
            $null = Send-Rcon $client $commandId 2 $cmd
            $commandId++
        }
    } finally { $client.Dispose() }
    Start-Sleep -Seconds $SettleSeconds

    $totalRounds = $WarmupRounds + $Rounds
    for ($roundIndex = 0; $roundIndex -lt $totalRounds; $roundIndex++) {
        $measuredRound = $roundIndex - $WarmupRounds + 1
        $isWarmup = $roundIndex -lt $WarmupRounds

        $shortType = $MobType -replace '^minecraft:', ''
        if ($BenchmarkMode -eq "pathfinder") {
            $startPathbench = "pathbench start {0} {1} {2} {3} {4} {5} {6} {7}" -f `
                $shortType, $PathMobAmount, $PathRequestsPerTick, $PathTargetRadius, $PathBenchWorld, $BotX, $BotY, $BotZ
            $startResponse = Invoke-RconCommand $startPathbench ([ref] $commandId)
            if ($startResponse -notmatch "^Pathbench started:") {
                throw "pathbench plugin did not start: $startResponse"
            }
            Write-Output $startResponse
            $pathbenchRunning = $true
        } elseif ($BenchmarkMode -eq "itembench") {
            $startItembench = "itembench start {0} {1} {2} {3} {4} {5} {6}" -f `
                $ItemAmount, $ItemPerTick, $ItemLayout, $ItembenchWorld, $BotX, $BotY, $BotZ
            $itembenchResponse = Invoke-Itembench $startItembench ([ref] $commandId)
            if ($itembenchResponse -notmatch "^Itembench starting:") {
                throw "itembench plugin did not start: $itembenchResponse"
            }
            Write-Output $itembenchResponse
        } else {
            # This mode measures the complete AI/goal/collision stack. It does
            # not promise a fixed number of findPath calls.
            $client = Open-Rcon
            try {
                $null = Send-Rcon $client $commandId 2 ("kill @e[type={0}]" -f $MobType)
                $commandId++
                for ($n = 0; $n -lt $MobAmount; $n++) {
                    $angle = ($n / [double]$MobAmount) * 2.0 * [Math]::PI
                    $r = $SpawnRadius + (Get-Random -Minimum 0 -Maximum 3)
                    $sx = $BotX + [int][Math]::Round($r * [Math]::Cos($angle))
                    $sz = $BotZ + [int][Math]::Round($r * [Math]::Sin($angle))
                    $summon = "execute in {0} run summon {1} {2} {3} {4} {{PersistenceRequired:1b}}" -f $BotWorld, $MobType, $sx, $BotY, $sz
                    $null = Send-Rcon $client $commandId 2 $summon
                    $commandId++
                }
                Write-Output ("Spawned {0} {1} in a ring r={2}" -f $MobAmount, $shortType, $SpawnRadius)
            } finally { $client.Dispose() }
        }

        if ($BenchmarkMode -eq "itembench") {
            Write-Output (Wait-ItembenchMeasuring ([ref] $commandId))
        }
        Start-Sleep -Seconds $SettleSeconds

        if ($isWarmup) {
            Write-Output ("Warmup {0}/{1}: mode={2}" -f ($roundIndex + 1), $WarmupRounds, $BenchmarkMode)
            Invoke-BenchmarkLoad $JfrSeconds ([ref] $commandId)
            if ($BenchmarkMode -eq "pathfinder") {
                Write-Output (Invoke-RconCommand "pathbench stop" ([ref] $commandId))
                $pathbenchRunning = $false
            }
            if ($BenchmarkMode -eq "itembench") {
                Write-Output (Invoke-Itembench "itembench stop" ([ref] $commandId))
                Wait-ItembenchCleanup ([ref] $commandId)
            }
            continue
        }

        # Lattice-only command; Leaf/Paper answer with a parse error, which is
        # not a failure of the run.
        $pathfinderReset = Invoke-RconCommand "lattice pathfinder reset" ([ref] $commandId)
        if ($pathfinderReset -notmatch "Unknown or incomplete command") {
            Write-Output $pathfinderReset
        }
        if ($BenchmarkMode -eq "pathfinder") {
            Write-Output (Invoke-RconCommand "pathbench reset" ([ref] $commandId))
        }

        $temporaryRecording = $null
        if ($EnableJfr) {
            $temporaryRecording = Join-Path $env:TEMP ("lattice-entity-{0}.jfr" -f [Guid]::NewGuid())
            $recordingName = "lattice-entity-{0}" -f $measuredRound
            & (Join-Path $JavaHome "bin\jcmd.exe") $process.Id JFR.start "name=$recordingName" settings=profile disk=true "filename=$temporaryRecording"
        }
        if ($SparkProfile) {
            # --thread * is required here: with Leaf's parallel world ticking the
            # entity work runs off the main thread, and spark's default only
            # samples the server thread, which would show an empty profile.
            $sparkStart = "spark profiler start --interval {0} --thread *" -f $SparkIntervalMillis
            Write-Output ("spark start: " + (Remove-ColorCodes (Invoke-RconCommand $sparkStart ([ref] $commandId))))
        }

        Write-Output ("Round {0}/{1}: recording {2}s mode={3}" -f $measuredRound, $Rounds, $JfrSeconds, $BenchmarkMode)
        Invoke-BenchmarkLoad $JfrSeconds ([ref] $commandId)

        if ($EnableJfr) {
            & (Join-Path $JavaHome "bin\jcmd.exe") $process.Id JFR.stop "name=$recordingName"
        }
        # Tick timings, sampled while the load is still resident. JFR sample
        # counts only show where time went, not how much -- these two are the
        # only readings that answer "how fast was the tick".
        Write-Output ("mspt: " + (Remove-ColorCodes (Invoke-RconCommand "mspt" ([ref] $commandId))))
        Write-Output ("tps: " + (Remove-ColorCodes (Invoke-RconCommand "tps" ([ref] $commandId))))
        $heapInfo = @(& (Join-Path $JavaHome "bin\jcmd.exe") $process.Id GC.heap_info 2>&1)
        Write-Output ("heap: " + (($heapInfo | ForEach-Object { $_.ToString().Trim() }) -join " | "))
        $gcInfo = Remove-ColorCodes (Invoke-RconCommand "spark health" ([ref] $commandId))
        if ($gcInfo) { Write-Output ("gc: " + $gcInfo) }
        $pathfinderStats = Invoke-RconCommand "lattice pathfinder" ([ref] $commandId)
        if ($pathfinderStats -notmatch "Unknown or incomplete command") {
            Write-Output ("Pathfinder stats: " + $pathfinderStats)
        }
        if ($SparkProfile) {
            # Stop after reading mspt so the profile covers the same window the
            # tick timings describe. Count the links already in the log first:
            # the upload is asynchronous, so the new one is whatever appears
            # beyond this mark.
            $knownUrls = @(Get-SparkUrls $stdout).Count
            $sparkStop = Remove-ColorCodes (Invoke-RconCommand "spark profiler stop" ([ref] $commandId))
            Write-Output ("spark stop: " + $sparkStop)
            $sparkUrl = Wait-SparkUrl $stdout $knownUrls 120
            if ($sparkUrl) {
                Write-Output ("SPARK-LINK [{0}-{1}] {2}" -f $JarName, $measuredRound, $sparkUrl)
            } else {
                Write-Warning ("spark 链接在 120s 内没有出现（第 {0} 轮），请查看 {1}" -f $measuredRound, $stdout)
            }
        }
        if ($BenchmarkMode -eq "itembench") {
            # Capture retained heap after the steady-state profiler has stopped,
            # but before cleanup removes the workload. This separates live-set
            # size from allocation timing without contaminating the CPU profile.
            $gcRun = @(& (Join-Path $JavaHome "bin\jcmd.exe") $process.Id GC.run 2>&1)
            $gcRunExit = $LASTEXITCODE
            Write-Output ("gc-run: " + (($gcRun | ForEach-Object { $_.ToString().Trim() }) -join " | "))
            if ($gcRunExit -eq 0) {
                $heapAfterGc = @(& (Join-Path $JavaHome "bin\jcmd.exe") $process.Id GC.heap_info 2>&1)
                Write-Output ("heap-after-gc: " + (($heapAfterGc | ForEach-Object { $_.ToString().Trim() }) -join " | "))
            }
        }
        if ($BenchmarkMode -eq "pathfinder") {
            Write-Output (Invoke-RconCommand "pathbench stop" ([ref] $commandId))
            $pathbenchRunning = $false
        }
        if ($BenchmarkMode -eq "itembench") {
            Write-Output (Invoke-Itembench "itembench stop" ([ref] $commandId))
            Wait-ItembenchCleanup ([ref] $commandId)
        }

        if ($EnableJfr) {
            $roundRecording = Get-RoundRecordingPath $measuredRound
            $roundDirectory = Split-Path -Parent $roundRecording
            if ($roundDirectory) { New-Item -ItemType Directory -Path $roundDirectory -Force | Out-Null }
            Move-Item -LiteralPath $temporaryRecording -Destination $roundRecording -Force
        }
    }

    $client = Open-Rcon
    try {
        if ($pathbenchRunning) {
            $null = Send-Rcon $client $commandId 2 "pathbench stop"
            $commandId++
            $pathbenchRunning = $false
        }
        $null = Send-Rcon $client $commandId 2 ("kill @e[tag={0}]" -f $TargetTag)
        $commandId++
        if ($BenchmarkMode -eq "entity") {
            $null = Send-Rcon $client $commandId 2 ("kill @e[type={0}]" -f $MobType)
            $commandId++
        }
        if ($BenchmarkMode -eq "itembench") {
            $null = Send-Rcon $client $commandId 2 "itembench stop"
            $commandId++
        }
        $null = Send-Rcon $client $commandId 2 $forceUnloadCommand
        $commandId++
        $forceLoaded = $false
        $null = Send-Rcon $client $commandId 2 "stop"
    } finally { $client.Dispose() }
    Wait-Process -Id $process.Id -Timeout 90
} finally {
    if ($process -and -not $process.HasExited) {
        try {
            $client = Open-Rcon
            try {
                $cleanupId = 899900
                if ($BenchmarkMode -eq "itembench") {
                    $null = Send-Rcon $client $cleanupId 2 "itembench stop"
                    $cleanupId++
                }
                $null = Send-Rcon $client $cleanupId 2 ("kill @e[tag={0}]" -f $TargetTag)
                $cleanupId++
                if ($BenchmarkMode -eq "entity") {
                    $null = Send-Rcon $client $cleanupId 2 ("kill @e[type={0}]" -f $MobType)
                }
            } finally { $client.Dispose() }
        } catch {
            Write-Warning ("Failed to clean benchmark entities through RCON: " + $_.Exception.Message)
        }
    }
    if ($forceLoaded -and $process -and -not $process.HasExited) {
        try {
            $client = Open-Rcon
            try { $null = Send-Rcon $client 900000 2 $forceUnloadCommand } finally { $client.Dispose() }
            $forceLoaded = $false
        } catch {
            Write-Warning ("Failed to remove benchmark forceload ticket: " + $_.Exception.Message)
        }
    }
    if ($pathbenchRunning -and $process -and -not $process.HasExited) {
        try { $null = Invoke-RconCommand "pathbench stop" ([ref] $commandId) } catch { }
        $pathbenchRunning = $false
    }
    if ($process -and -not $process.HasExited) { Stop-Process -Id $process.Id -Force }
    Copy-Item -LiteralPath $backup -Destination $properties -Force
}

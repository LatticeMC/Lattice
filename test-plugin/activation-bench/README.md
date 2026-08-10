# Activation benchmark bot runner

`ActivationBenchBotRunner` is a standalone, offline-only MCProtocolLib client. It creates real loopback Minecraft connections with names `${prefix}1` through `${prefix}N`, waits for login completion, acknowledges server teleports, and sends movement packets back to each server-assigned anchor. The runner does not authenticate with Mojang or Microsoft.

The target server must have `online-mode=false`. Authentication or online-mode flags are intentionally rejected by the runner; a rejected flag is not treated as a successful login.

## Manual procedure

1. Start the Paper/Purpur test server with the activation benchmark plugin and verify the server is configured with `online-mode=false`.
2. In-game, prepare the benchmark through the plugin command, for example:

   ```text
   /activationbench prepare <count> <overlap|disjoint> <regions 1-100> [world] [x] [y] [z]
   ```

3. Poll `/activationbench status` until the preparation phase is reported as ready.
4. Start the bot runner from the repository root. The Gradle task is supplied by the isolated `activationBench` source set:

   ```text
   ./gradlew :test-plugin:runActivationBench --args="--host 127.0.0.1 --port 25565 --bots 4 --prefix ab --hold-seconds 10 --game-ready-timeout-seconds 30 --position-ready-timeout-seconds 10 --output activation-bench/result.json"
   ```

   On Windows PowerShell, use the equivalent `gradlew.bat` command and quote the complete `--args` value as required by PowerShell.

5. While the runner is holding its connections, use `/activationbench start` to begin the server-side activation workload. Use `/activationbench status` to observe the plugin state.
6. When the workload is complete, run `/activationbench stop` and inspect the JSON printed by the runner and, when requested, written to `--output`. The `runActivationBench` task runs with `test-plugin` as its working directory, so use `activation-bench/...` rather than `test-plugin/activation-bench/...`; missing parent directories are created automatically.

The exact orchestration of server startup, command dispatch, process lifecycle, and ABBA (activation benchmark automation) is supplied by a later script. This runner does not claim to provide that lifecycle orchestration.

## Cold-JVM ABBA collection

`Invoke-ActivationBenchManualAbba.ps1` creates an `A, B, B, A` schedule for every pair, writes
`metadata.json`, raw `samples.csv`, each bot-runner JSON result, and `summary.json`. It intentionally
does not start or stop a server or inject console commands: the repository has no stable test-server
launcher/RCON contract, and pretending otherwise would invalidate a cold-JVM comparison.

By default the script runs the full player-count matrix `1, 2, 4, 8, 16, 32, 50, 64, 100`; each count gets one warmup pair and five measured ABBA pairs. This is intentionally large. Pass `-Bots 50` (or another supported count) to run a focused slice:

```powershell
pwsh -File .\test-plugin\activation-bench\Invoke-ActivationBenchManualAbba.ps1 `
  -Bots 50 -Layout overlap -EntityCount 100000 -WarmupPairs 1 -Pairs 5 -MeasureSeconds 60
```

For every prompted trial, cold-start the server with the displayed JVM flag:

- `A`: `-Dlattice.entityActivationKdTree=false`
- `B`: `-Dlattice.entityActivationKdTree=true`

The script requires `online-mode=false`, uses the plugin's default `LatticeActBot1..N` names, and
excludes a missing or unsuccessful bot JSON result from the measured summary while retaining its raw row. Do not run it concurrently
with `/itembench`; the two test commands do not coordinate plugin chunk tickets.
If `-BotPrefix` differs from `LatticeActBot`, use the matching server JVM property
`-Dlattice.activationBenchBotPrefix=<prefix>`; the script prints it for every trial.

## Options

`--host`, `--port`, `--bots` (only `1, 2, 4, 8, 16, 32, 50, 64, 100`), `--prefix`, `--hold-seconds`, `--game-ready-timeout-seconds`, `--position-ready-timeout-seconds`, and `--output` are the supported options. Defaults are `127.0.0.1`, `25565`, `1`, `LatticeActBot`, `10`, `30`, `10`, and no output file. The GAME-ready timeout accepts `5..120` seconds; the initial-position timeout accepts `1..60` seconds. Heavy plugin stacks and first spawn/chunk preparation can legitimately take longer than five seconds after configuration starts; the runner waits for every server-assigned initial position before sending movement or beginning its settle window. Neither timeout hides a disconnect or packet error: both still fail the run. Bot names must remain within the Minecraft 16-character username limit. Teleport target/latest coordinates in the JSON are the server-assigned positions; the runner does not replace plugin overlap/disjoint anchors with a global coordinate.

Counts below 50 are control points, not an expected KD-tree win. Leaf's author places the likely break-even band around 50–100 simultaneously online players, so interpret the 50, 64, and 100 rows as the primary decision evidence.

Exit code `0` means every requested bot completed login and remained connected through the two-second settle window and requested hold period. Exit code `1` reports a connection/login/hold failure and still emits a JSON result when the runner reaches its normal result path. Exit code `2` indicates invalid arguments, including online-mode/authentication flags.

The JSON includes the MCProtocolLib and Minecraft protocol versions, timestamps, requested bot names, per-bot connection events, disconnect errors, teleport IDs and server/target/latest coordinates, and the checks used to determine success. A successful 1.21.11 configuration handshake records `known_packs_requested`, `known_packs_response_sent`, and `client_information_sent` before the runner enters GAME and accepts the server teleport.

For a failed configuration handshake, each bot also reports `configurationPackets`: the first 256 consecutive packet-type runs observed while either protocol direction is in CONFIGURATION, with adjacent identical packet types compressed into `{type,count}`. `configurationPacketsDropped` reports later distinct runs that were not retained. `protocolStateEvents` reports timestamped observed inbound/outbound state-pair changes. These are diagnostics only: they do not change handshake behavior or timeout handling.

package org.purpurmc.testplugin.activationbench;

import org.cloudburstmc.math.vector.Vector3d;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.ConnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.PacketErrorEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginFinishedPacket;
import org.geysermc.mcprotocollib.network.packet.Packet;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Offline MCProtocolLib clients used by the activation benchmark.
 *
 * <p>This class deliberately does not authenticate with Mojang/Microsoft. The
 * server must be configured with {@code online-mode=false}; authentication
 * flags are rejected instead of being silently ignored.</p>
 */
public final class ActivationBenchBotRunner {
    private static final String LIBRARY_VERSION = "1.21.11-1";
    private static final long LOGIN_TIMEOUT_SECONDS = 30L;
    private static final long SETTLE_WINDOW_MILLIS = 2_000L;
    private static final long MOVE_INTERVAL_MILLIS = 1_000L;
    private static final long POLL_MILLIS = 100L;

    private ActivationBenchBotRunner() {
    }

    public static void main(String[] args) {
        int exitCode;
        try {
            Config config = Config.parse(args);
            Result result = run(config);
            String json = result.toJson(config);
            System.out.println(json);
            if (config.output != null) {
                Files.writeString(config.output, json + System.lineSeparator(), StandardCharsets.UTF_8);
            }
            exitCode = result.success ? 0 : 1;
        } catch (IllegalArgumentException exception) {
            System.err.println("Activation benchmark argument error: " + exception.getMessage());
            exitCode = 2;
        } catch (Exception exception) {
            System.err.println("Activation benchmark failed: " + message(exception));
            exitCode = 1;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static Result run(Config config) throws InterruptedException {
        String startedAt = Instant.now().toString();
        CountDownLatch loginLatch = new CountDownLatch(config.bots);
        List<BotState> states = new ArrayList<>(config.bots);

        for (int index = 1; index <= config.bots; index++) {
            String name = config.prefix + index;
            BotState state = new BotState(name, loginLatch);
            ClientNetworkSession session = ClientNetworkSessionFactory.factory()
                .setAddress(config.host, config.port)
                .setProtocol(new MinecraftProtocol(name))
                .create();
            state.session = session;
            session.addListener(state.listener());
            states.add(state);
        }

        for (BotState state : states) {
            try {
                state.session.connect(false);
            } catch (RuntimeException exception) {
                state.recordError("connect: " + message(exception));
            }
        }

        boolean allLoggedIn = loginLatch.await(LOGIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        boolean allConnectedAfterSettle = false;
        boolean gameReady = allLoggedIn && awaitGameState(states);
        if (gameReady) {
            // LoginFinished is still handled in LOGIN. Send movement only after
            // the default listener has advanced through CONFIGURATION to GAME.
            for (BotState state : states) {
                state.sendMoveAtLatest();
            }
            long settleDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SETTLE_WINDOW_MILLIS);
            long nextMoveAt = System.nanoTime();
            while (System.nanoTime() < settleDeadline) {
                if (!allHealthyAndPositioned(states)) {
                    break;
                }
                nextMoveAt = sendPeriodicMoves(states, nextMoveAt);
                Thread.sleep(POLL_MILLIS);
            }
            allConnectedAfterSettle = allHealthyAndPositioned(states);
        }

        boolean held = allConnectedAfterSettle;
        if (held) {
            long holdDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.holdSeconds);
            long nextMoveAt = System.nanoTime();
            while (System.nanoTime() < holdDeadline) {
                if (!allHealthyAndPositioned(states)) {
                    held = false;
                    break;
                }
                nextMoveAt = sendPeriodicMoves(states, nextMoveAt);
                Thread.sleep(POLL_MILLIS);
            }
            held = held && allHealthyAndPositioned(states);
        }

        boolean success = allLoggedIn && gameReady && allConnectedAfterSettle && held;
        String finishedAt = Instant.now().toString();
        List<BotSnapshot> snapshots = new ArrayList<>(states.size());
        for (BotState state : states) {
            snapshots.add(state.snapshot());
        }
        for (BotState state : states) {
            state.close();
        }
        return new Result(success, startedAt, finishedAt, states, snapshots,
            allLoggedIn, gameReady, allConnectedAfterSettle, held);
    }

    private static boolean allHealthyAndPositioned(List<BotState> states) {
        for (BotState state : states) {
            if (!state.session.isConnected() || !state.loginComplete || !state.hasPosition() || state.hasErrors()) {
                return false;
            }
        }
        return true;
    }

    private static long sendPeriodicMoves(List<BotState> states, long nextMoveAt) {
        long now = System.nanoTime();
        if (now < nextMoveAt) {
            return nextMoveAt;
        }
        for (BotState state : states) {
            state.sendMoveAtLatest();
        }
        return now + TimeUnit.MILLISECONDS.toNanos(MOVE_INTERVAL_MILLIS);
    }

    private static boolean awaitGameState(List<BotState> states) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (System.nanoTime() < deadline) {
            boolean ready = true;
            for (BotState state : states) {
                if (!state.session.isConnected()
                    || state.session.getPacketProtocol().getOutboundState() != ProtocolState.GAME) {
                    ready = false;
                    break;
                }
            }
            if (ready) {
                return true;
            }
            Thread.sleep(POLL_MILLIS);
        }
        return false;
    }

    private static String message(Throwable throwable) {
        String value = throwable.getMessage();
        return throwable.getClass().getSimpleName() + (value == null || value.isBlank() ? "" : ": " + value);
    }

    private static String json(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (character < 0x20) {
                        builder.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }

    private static String number(double value) {
        return Double.isFinite(value) ? Double.toString(value) : "null";
    }

    private static String coordinates(Coordinates coordinates) {
        if (coordinates == null) {
            return "null";
        }
        return "[" + number(coordinates.x) + "," + number(coordinates.y) + "," + number(coordinates.z) + "]";
    }

    private static final class Config {
        private final String host;
        private final int port;
        private final int bots;
        private final String prefix;
        private final long holdSeconds;
        private final Path output;

        private Config(String host, int port, int bots, String prefix, long holdSeconds, Path output) {
            this.host = host;
            this.port = port;
            this.bots = bots;
            this.prefix = prefix;
            this.holdSeconds = holdSeconds;
            this.output = output;
        }

        private static Config parse(String[] args) {
            String host = "127.0.0.1";
            int port = 25565;
            int bots = 1;
            String prefix = "LatticeActBot";
            long holdSeconds = 10L;
            Path output = null;

            for (int index = 0; index < args.length; index++) {
                String option = args[index];
                if (isAuthenticationFlag(option)) {
                    throw new IllegalArgumentException(option + " is not supported; this runner is offline-only");
                }
                if (!option.startsWith("--")) {
                    throw new IllegalArgumentException("unexpected argument: " + option);
                }
                String value = requireValue(args, ++index, option);
                switch (option) {
                    case "--host" -> host = value;
                    case "--port" -> port = parseInt(value, option, 1, 65535);
                    case "--bots" -> bots = parseInt(value, option, 1, 16);
                    case "--prefix" -> prefix = value;
                    case "--hold-seconds" -> holdSeconds = parseLong(value, option, 0L, 86_400L);
                    case "--output" -> output = Path.of(value);
                    default -> throw new IllegalArgumentException("unknown option: " + option);
                }
            }

            if (host.isBlank()) {
                throw new IllegalArgumentException("--host must not be blank");
            }
            if (bots != 1 && bots != 2 && bots != 4 && bots != 8 && bots != 16) {
                throw new IllegalArgumentException("--bots must be one of 1, 2, 4, 8, 16");
            }
            if (!prefix.matches("[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException("--prefix must contain only ASCII letters, digits, and underscores");
            }
            if ((prefix + bots).length() > 16) {
                throw new IllegalArgumentException("bot names must be at most 16 characters");
            }
            return new Config(host, port, bots, prefix, holdSeconds, output);
        }

        private static boolean isAuthenticationFlag(String option) {
            String normalized = option.toLowerCase(Locale.ROOT);
            return normalized.equals("--online-mode") || normalized.startsWith("--online-mode=")
                || normalized.equals("--online") || normalized.startsWith("--online=")
                || normalized.startsWith("--auth")
                || normalized.equals("--access-token") || normalized.startsWith("--access-token=")
                || normalized.equals("--session-token") || normalized.startsWith("--session-token=")
                || normalized.equals("--uuid") || normalized.startsWith("--uuid=");
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("--")) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }

        private static int parseInt(String value, String option, int minimum, int maximum) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed < minimum || parsed > maximum) {
                    throw new IllegalArgumentException(option + " must be between " + minimum + " and " + maximum);
                }
                return parsed;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(option + " must be an integer");
            }
        }

        private static long parseLong(String value, String option, long minimum, long maximum) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed < minimum || parsed > maximum) {
                    throw new IllegalArgumentException(option + " must be between " + minimum + " and " + maximum);
                }
                return parsed;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(option + " must be an integer");
            }
        }
    }

    private static final class BotState {
        private final String name;
        private final CountDownLatch loginLatch;
        private final List<ConnectionEvent> connectionEvents = Collections.synchronizedList(new ArrayList<>());
        private final List<String> disconnectErrors = Collections.synchronizedList(new ArrayList<>());
        private final List<TeleportEvent> teleports = Collections.synchronizedList(new ArrayList<>());
        private volatile ClientNetworkSession session;
        private volatile boolean loginComplete;
        private volatile boolean intentionalClose;
        private volatile Coordinates latestCoordinates;

        private BotState(String name, CountDownLatch loginLatch) {
            this.name = name;
            this.loginLatch = loginLatch;
        }

        private SessionAdapter listener() {
            return new SessionAdapter() {
                @Override
                public void connected(ConnectedEvent event) {
                    connectionEvents.add(new ConnectionEvent(Instant.now().toString(), "connected"));
                }

                @Override
                public void packetReceived(Session ignored, Packet packet) {
                    if (packet instanceof ClientboundLoginFinishedPacket) {
                        loginComplete = true;
                        connectionEvents.add(new ConnectionEvent(Instant.now().toString(), "login_complete"));
                        loginLatch.countDown();
                    } else if (packet instanceof ClientboundPlayerPositionPacket teleport) {
                        handleTeleport(teleport);
                    }
                }

                @Override
                public void packetError(PacketErrorEvent event) {
                    recordError("packet: " + message(event.getCause()));
                }

                @Override
                public void disconnected(DisconnectedEvent event) {
                    connectionEvents.add(new ConnectionEvent(Instant.now().toString(), "disconnected"));
                    if (!loginComplete) {
                        loginLatch.countDown();
                    }
                    if (!intentionalClose) {
                        String reason = event.getReason() == null ? "unknown" : event.getReason().toString();
                        Throwable cause = event.getCause();
                        recordError("disconnect: " + reason + (cause == null ? "" : " (" + message(cause) + ")"));
                    }
                }
            };
        }

        private void handleTeleport(ClientboundPlayerPositionPacket packet) {
            Coordinates base = latestCoordinates;
            if (base == null && (relative(packet, PositionElement.X)
                || relative(packet, PositionElement.Y)
                || relative(packet, PositionElement.Z))) {
                send(new ServerboundAcceptTeleportationPacket(packet.getId()));
                recordError("teleport: cannot resolve relative position without a prior absolute position");
                return;
            }
            if (base == null) {
                base = new Coordinates(0.0D, 0.0D, 0.0D);
            }
            Vector3d position = packet.getPosition();
            double x = relative(packet, PositionElement.X) ? base.x + position.getX() : position.getX();
            double y = relative(packet, PositionElement.Y) ? base.y + position.getY() : position.getY();
            double z = relative(packet, PositionElement.Z) ? base.z + position.getZ() : position.getZ();
            Coordinates serverCoordinates = new Coordinates(x, y, z);
            send(new ServerboundAcceptTeleportationPacket(packet.getId()));
            latestCoordinates = serverCoordinates;
            sendMoveAtLatest();
            teleports.add(new TeleportEvent(Instant.now().toString(), packet.getId(), serverCoordinates,
                serverCoordinates, latestCoordinates));
        }

        private static boolean relative(ClientboundPlayerPositionPacket packet, PositionElement element) {
            return packet.getRelatives().contains(element);
        }

        private void sendMoveAtLatest() {
            Coordinates target = latestCoordinates;
            if (target != null) {
                send(new ServerboundMovePlayerPosPacket(true, false, target.x, target.y, target.z));
            }
        }

        private void send(Packet packet) {
            ClientNetworkSession current = session;
            if (current != null && current.isConnected()) {
                current.send(packet);
            }
        }

        private void recordError(String error) {
            disconnectErrors.add(error);
        }

        private boolean hasPosition() {
            return latestCoordinates != null;
        }

        private boolean hasErrors() {
            return !disconnectErrors.isEmpty();
        }

        private void close() {
            intentionalClose = true;
            ClientNetworkSession current = session;
            if (current != null && current.isConnected()) {
                current.disconnect("activation benchmark complete");
            }
        }

        private BotSnapshot snapshot() {
            return new BotSnapshot(name, loginComplete, session != null && session.isConnected(), latestCoordinates,
                new ArrayList<>(connectionEvents), new ArrayList<>(disconnectErrors), new ArrayList<>(teleports));
        }
    }

    private record Coordinates(double x, double y, double z) {
    }

    private record ConnectionEvent(String timestamp, String type) {
    }

    private record TeleportEvent(String timestamp, int id, Coordinates serverCoordinates,
                                 Coordinates targetCoordinates, Coordinates latestCoordinates) {
    }

    private record BotSnapshot(String name, boolean loginComplete, boolean connected, Coordinates latestCoordinates,
                               List<ConnectionEvent> connectionEvents, List<String> disconnectErrors,
                               List<TeleportEvent> teleports) {
    }

    private static final class Result {
        private final boolean success;
        private final String startedAt;
        private final String finishedAt;
        private final List<BotState> states;
        private final List<BotSnapshot> snapshots;
        private final boolean allLoggedIn;
        private final boolean gameReady;
        private final boolean allConnectedAfterSettle;
        private final boolean held;

        private Result(boolean success, String startedAt, String finishedAt, List<BotState> states,
                       List<BotSnapshot> snapshots, boolean allLoggedIn, boolean gameReady,
                       boolean allConnectedAfterSettle,
                       boolean held) {
            this.success = success;
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
            this.states = states;
            this.snapshots = snapshots;
            this.allLoggedIn = allLoggedIn;
            this.gameReady = gameReady;
            this.allConnectedAfterSettle = allConnectedAfterSettle;
            this.held = held;
        }

        private String toJson(Config config) {
            StringBuilder json = new StringBuilder(4096);
            json.append('{');
            field(json, "success", Boolean.toString(success)).append(',');
            field(json, "library", "{\"name\":\"MCProtocolLib\",\"version\":" + json(LIBRARY_VERSION)
                + ",\"minecraftVersion\":" + json(MinecraftCodec.CODEC.getMinecraftVersion())
                + ",\"protocolVersion\":" + MinecraftCodec.CODEC.getProtocolVersion() + "}").append(',');
            field(json, "host", json(config.host)).append(',');
            field(json, "port", Integer.toString(config.port)).append(',');
            field(json, "requestedBots", Integer.toString(config.bots)).append(',');
            field(json, "prefix", json(config.prefix)).append(',');
            field(json, "holdSeconds", Long.toString(config.holdSeconds)).append(',');
            field(json, "settleWindowSeconds", "2").append(',');
            field(json, "startedAt", json(startedAt)).append(',');
            field(json, "finishedAt", json(finishedAt)).append(',');
            field(json, "checks", "{\"allLoggedIn\":" + allLoggedIn + ",\"gameStateReady\":" + gameReady
                + ",\"allConnectedAfterSettle\":" + allConnectedAfterSettle
                + ",\"heldForRequestedSeconds\":" + held + "}").append(',');
            json.append("\"botNames\":[");
            for (int index = 0; index < snapshots.size(); index++) {
                if (index > 0) {
                    json.append(',');
                }
                json.append(json(snapshots.get(index).name));
            }
            json.append("],\"bots\":[");
            for (int index = 0; index < snapshots.size(); index++) {
                if (index > 0) {
                    json.append(',');
                }
                appendBot(json, snapshots.get(index));
            }
            return json.append("]}").toString();
        }

        private static void appendBot(StringBuilder json, BotSnapshot bot) {
            json.append('{');
            field(json, "name", json(bot.name)).append(',');
            field(json, "connectedAtCompletion", Boolean.toString(bot.connected)).append(',');
            field(json, "loginComplete", Boolean.toString(bot.loginComplete)).append(',');
            field(json, "latestCoordinates", coordinates(bot.latestCoordinates)).append(',');
            json.append("\"connectionEvents\":[");
            for (int index = 0; index < bot.connectionEvents.size(); index++) {
                if (index > 0) {
                    json.append(',');
                }
                ConnectionEvent event = bot.connectionEvents.get(index);
                json.append("{\"timestamp\":").append(json(event.timestamp)).append(",\"type\":")
                    .append(json(event.type)).append('}');
            }
            json.append("],\"disconnectErrors\":[");
            for (int index = 0; index < bot.disconnectErrors.size(); index++) {
                if (index > 0) {
                    json.append(',');
                }
                json.append(json(bot.disconnectErrors.get(index)));
            }
            json.append("],\"teleports\":[");
            for (int index = 0; index < bot.teleports.size(); index++) {
                if (index > 0) {
                    json.append(',');
                }
                TeleportEvent teleport = bot.teleports.get(index);
                json.append("{\"timestamp\":").append(json(teleport.timestamp)).append(",\"id\":")
                    .append(teleport.id).append(",\"serverCoordinates\":")
                    .append(coordinates(teleport.serverCoordinates)).append(",\"targetCoordinates\":")
                    .append(coordinates(teleport.targetCoordinates)).append(",\"latestCoordinates\":")
                    .append(coordinates(teleport.latestCoordinates)).append('}');
            }
            json.append("]}");
        }

        private static StringBuilder field(StringBuilder json, String key, String value) {
            return json.append(json(key)).append(':').append(value);
        }
    }
}

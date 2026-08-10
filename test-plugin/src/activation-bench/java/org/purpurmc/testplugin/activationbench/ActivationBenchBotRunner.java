package org.purpurmc.testplugin.activationbench;

import org.cloudburstmc.math.vector.Vector3d;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.ConnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.PacketErrorEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ChatVisibility;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ParticleStatus;
import org.geysermc.mcprotocollib.protocol.data.game.setting.SkinPart;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundFinishConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundSelectKnownPacks;
import org.geysermc.mcprotocollib.protocol.packet.configuration.serverbound.ServerboundFinishConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.serverbound.ServerboundSelectKnownPacks;
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
    private static final long DEFAULT_GAME_READY_TIMEOUT_SECONDS = 30L;
    private static final long MIN_GAME_READY_TIMEOUT_SECONDS = 5L;
    private static final long MAX_GAME_READY_TIMEOUT_SECONDS = 120L;
    private static final long SETTLE_WINDOW_MILLIS = 2_000L;
    private static final long MOVE_INTERVAL_MILLIS = 1_000L;
    private static final long POLL_MILLIS = 100L;
    private static final int MAX_CONFIGURATION_PACKET_RUNS = 256;
    private static final int MAX_BOTS = 100;

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
                Path parent = config.output.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
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
            // The runner owns this reply so it can prove the CONFIGURATION handshake
            // completed. Disable the default listener's otherwise identical blank reply.
            session.setFlag(MinecraftConstants.SEND_BLANK_KNOWN_PACKS_RESPONSE, false);
            state.session = session;
            state.recordProtocolStateIfChanged();
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
        boolean gameReady = allLoggedIn && awaitGameState(states, config.gameReadyTimeoutSeconds);
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
            state.recordProtocolStateIfChanged();
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

    private static boolean awaitGameState(List<BotState> states, long timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            boolean ready = true;
            for (BotState state : states) {
                state.recordProtocolStateIfChanged();
                state.sendClientInformationIfConfiguration();
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
        private final long gameReadyTimeoutSeconds;
        private final Path output;

        private Config(String host, int port, int bots, String prefix, long holdSeconds, long gameReadyTimeoutSeconds,
                       Path output) {
            this.host = host;
            this.port = port;
            this.bots = bots;
            this.prefix = prefix;
            this.holdSeconds = holdSeconds;
            this.gameReadyTimeoutSeconds = gameReadyTimeoutSeconds;
            this.output = output;
        }

        private static Config parse(String[] args) {
            String host = "127.0.0.1";
            int port = 25565;
            int bots = 1;
            String prefix = "LatticeActBot";
            long holdSeconds = 10L;
            long gameReadyTimeoutSeconds = DEFAULT_GAME_READY_TIMEOUT_SECONDS;
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
                    case "--bots" -> bots = parseInt(value, option, 1, MAX_BOTS);
                    case "--prefix" -> prefix = value;
                    case "--hold-seconds" -> holdSeconds = parseLong(value, option, 0L, 86_400L);
                    case "--game-ready-timeout-seconds" -> gameReadyTimeoutSeconds = parseLong(value, option,
                        MIN_GAME_READY_TIMEOUT_SECONDS, MAX_GAME_READY_TIMEOUT_SECONDS);
                    case "--output" -> output = Path.of(value);
                    default -> throw new IllegalArgumentException("unknown option: " + option);
                }
            }

            if (host.isBlank()) {
                throw new IllegalArgumentException("--host must not be blank");
            }
            if (!isSupportedBotCount(bots)) {
                throw new IllegalArgumentException("--bots must be one of 1, 2, 4, 8, 16, 32, 50, 64, 100");
            }
            if (!prefix.matches("[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException("--prefix must contain only ASCII letters, digits, and underscores");
            }
            if ((prefix + bots).length() > 16) {
                throw new IllegalArgumentException("bot names must be at most 16 characters");
            }
            return new Config(host, port, bots, prefix, holdSeconds, gameReadyTimeoutSeconds, output);
        }

        private static boolean isSupportedBotCount(int value) {
            return value == 1 || value == 2 || value == 4 || value == 8 || value == 16
                || value == 32 || value == 50 || value == 64 || value == 100;
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
        private final List<ConfigurationPacketRun> configurationPackets = Collections.synchronizedList(new ArrayList<>());
        private final List<ProtocolStateEvent> protocolStateEvents = Collections.synchronizedList(new ArrayList<>());
        private volatile ClientNetworkSession session;
        private volatile boolean loginComplete;
        private volatile boolean intentionalClose;
        private volatile boolean clientInformationSent;
        private volatile boolean knownPacksResponseSent;
        private volatile ProtocolState lastInboundState;
        private volatile ProtocolState lastOutboundState;
        private volatile int configurationPacketsDropped;
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
                public void packetReceived(Session current, Packet packet) {
                    recordProtocolStateIfChanged(current);
                    recordConfigurationPacket(current, packet);
                    if (packet instanceof ClientboundLoginFinishedPacket) {
                        loginComplete = true;
                        connectionEvents.add(new ConnectionEvent(Instant.now().toString(), "login_complete"));
                        loginLatch.countDown();
                    } else if (packet instanceof ClientboundSelectKnownPacks) {
                        respondToKnownPacks(current);
                    } else if (packet instanceof ClientboundFinishConfigurationPacket) {
                        finishConfiguration(current);
                    } else if (packet instanceof ClientboundPlayerPositionPacket teleport) {
                        handleTeleport(teleport);
                    }
                    recordProtocolStateIfChanged(current);
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

        private void sendClientInformationIfConfiguration() {
            ClientNetworkSession current = session;
            if (clientInformationSent || current == null || !current.isConnected()
                || current.getPacketProtocol().getOutboundState() != ProtocolState.CONFIGURATION) {
                return;
            }
            synchronized (this) {
                if (clientInformationSent || !current.isConnected()
                    || current.getPacketProtocol().getOutboundState() != ProtocolState.CONFIGURATION) {
                    return;
                }
                current.send(new ServerboundClientInformationPacket(
                    "en_us", 10, ChatVisibility.FULL, true, List.of(SkinPart.VALUES),
                    HandPreference.RIGHT_HAND, false, true, ParticleStatus.ALL
                ));
                clientInformationSent = true;
                connectionEvents.add(new ConnectionEvent(Instant.now().toString(), "client_information_sent"));
            }
        }

        private void respondToKnownPacks(Session current) {
            connectionEvents.add(new ConnectionEvent(Instant.now().toString(), "known_packs_requested"));
            synchronized (this) {
                if (knownPacksResponseSent || !current.isConnected()
                    || current.getPacketProtocol().getOutboundState() != ProtocolState.CONFIGURATION) {
                    return;
                }
                current.send(new ServerboundSelectKnownPacks(Collections.emptyList()));
                knownPacksResponseSent = true;
                connectionEvents.add(new ConnectionEvent(Instant.now().toString(), "known_packs_response_sent"));
            }
        }

        private void recordConfigurationPacket(Session current, Packet packet) {
            MinecraftProtocol protocol = current.getPacketProtocol();
            if (protocol.getInboundState() != ProtocolState.CONFIGURATION
                && protocol.getOutboundState() != ProtocolState.CONFIGURATION) {
                return;
            }
            String type = packet.getClass().getSimpleName();
            synchronized (configurationPackets) {
                int size = configurationPackets.size();
                if (size > 0) {
                    ConfigurationPacketRun previous = configurationPackets.get(size - 1);
                    if (previous.type().equals(type)) {
                        configurationPackets.set(size - 1, new ConfigurationPacketRun(type, previous.count() + 1));
                        return;
                    }
                }
                if (size < MAX_CONFIGURATION_PACKET_RUNS) {
                    configurationPackets.add(new ConfigurationPacketRun(type, 1));
                } else {
                    configurationPacketsDropped++;
                }
            }
        }

        private void recordProtocolStateIfChanged() {
            ClientNetworkSession current = session;
            if (current != null) {
                recordProtocolStateIfChanged(current);
            }
        }

        private void recordProtocolStateIfChanged(Session current) {
            MinecraftProtocol protocol = current.getPacketProtocol();
            ProtocolState inbound = protocol.getInboundState();
            ProtocolState outbound = protocol.getOutboundState();
            synchronized (protocolStateEvents) {
                if (inbound == lastInboundState && outbound == lastOutboundState) {
                    return;
                }
                lastInboundState = inbound;
                lastOutboundState = outbound;
                protocolStateEvents.add(new ProtocolStateEvent(Instant.now().toString(), inbound.name(), outbound.name()));
            }
        }

        private void finishConfiguration(Session current) {
            MinecraftProtocol protocol = current.getPacketProtocol();
            if (protocol.getInboundState() != ProtocolState.CONFIGURATION
                || protocol.getOutboundState() != ProtocolState.CONFIGURATION) {
                return;
            }
            current.switchInboundState(() -> protocol.setInboundState(ProtocolState.GAME));
            current.send(ServerboundFinishConfigurationPacket.INSTANCE);
            current.switchOutboundState(() -> protocol.setOutboundState(ProtocolState.GAME));
            connectionEvents.add(new ConnectionEvent(Instant.now().toString(), "configuration_finished"));
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
            recordProtocolStateIfChanged();
            return new BotSnapshot(name, loginComplete, session != null && session.isConnected(), latestCoordinates,
                new ArrayList<>(connectionEvents), new ArrayList<>(disconnectErrors), new ArrayList<>(teleports),
                new ArrayList<>(configurationPackets), configurationPacketsDropped, new ArrayList<>(protocolStateEvents));
        }
    }

    private record Coordinates(double x, double y, double z) {
    }

    private record ConnectionEvent(String timestamp, String type) {
    }

    private record TeleportEvent(String timestamp, int id, Coordinates serverCoordinates,
                                 Coordinates targetCoordinates, Coordinates latestCoordinates) {
    }

    private record ConfigurationPacketRun(String type, int count) {
    }

    private record ProtocolStateEvent(String timestamp, String inbound, String outbound) {
    }

    private record BotSnapshot(String name, boolean loginComplete, boolean connected, Coordinates latestCoordinates,
                               List<ConnectionEvent> connectionEvents, List<String> disconnectErrors,
                               List<TeleportEvent> teleports, List<ConfigurationPacketRun> configurationPackets,
                               int configurationPacketsDropped, List<ProtocolStateEvent> protocolStateEvents) {
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
            field(json, "gameReadyTimeoutSeconds", Long.toString(config.gameReadyTimeoutSeconds)).append(',');
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
            json.append("],\"configurationPackets\":[");
            for (int index = 0; index < bot.configurationPackets.size(); index++) {
                if (index > 0) {
                    json.append(',');
                }
                ConfigurationPacketRun packet = bot.configurationPackets.get(index);
                json.append("{\"type\":").append(json(packet.type)).append(",\"count\":")
                    .append(packet.count).append('}');
            }
            json.append("],\"configurationPacketsDropped\":").append(bot.configurationPacketsDropped)
                .append(",\"protocolStateEvents\":[");
            for (int index = 0; index < bot.protocolStateEvents.size(); index++) {
                if (index > 0) {
                    json.append(',');
                }
                ProtocolStateEvent state = bot.protocolStateEvents.get(index);
                json.append("{\"timestamp\":").append(json(state.timestamp)).append(",\"inbound\":")
                    .append(json(state.inbound)).append(",\"outbound\":").append(json(state.outbound)).append('}');
            }
            json.append("]}");
        }

        private static StringBuilder field(StringBuilder json, String key, String value) {
            return json.append(json(key)).append(':').append(value);
        }
    }
}

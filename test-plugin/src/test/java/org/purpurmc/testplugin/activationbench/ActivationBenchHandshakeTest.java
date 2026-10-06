package org.purpurmc.testplugin.activationbench;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.geysermc.mcprotocollib.network.event.session.SessionListener;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundFinishConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundSelectKnownPacks;
import org.geysermc.mcprotocollib.protocol.packet.configuration.serverbound.ServerboundFinishConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.serverbound.ServerboundSelectKnownPacks;
import org.junit.jupiter.api.Test;

class ActivationBenchHandshakeTest {
    @Test void configurationWritesPrecedeFinishAckAndPollingNeverWrites() throws Exception {
        Class<?> type = Class.forName(ActivationBenchBotRunner.class.getName() + "$BotState");
        var constructor = type.getDeclaredConstructor(String.class, CountDownLatch.class);
        constructor.setAccessible(true);
        Object state = constructor.newInstance("TestBot", new CountDownLatch(1));
        MinecraftProtocol protocol = new MinecraftProtocol("TestBot");
        protocol.setInboundState(ProtocolState.CONFIGURATION);
        protocol.setOutboundState(ProtocolState.CONFIGURATION);
        ClientNetworkSession session = mock(ClientNetworkSession.class);
        when(session.getPacketProtocol()).thenReturn(protocol);
        when(session.isConnected()).thenReturn(true);
        var field = type.getDeclaredField("session");
        field.setAccessible(true);
        field.set(state, session);

        Method await = ActivationBenchBotRunner.class.getDeclaredMethod("awaitGameState", List.class, long.class);
        await.setAccessible(true);
        assertEquals(false, await.invoke(null, List.of(state), 1L));
        verify(session, never()).send(any(Packet.class));

        List<Class<?>> writes = new ArrayList<>();
        doAnswer(call -> {
            assertEquals(ProtocolState.CONFIGURATION, protocol.getOutboundState());
            writes.add(call.getArgument(0).getClass());
            return null;
        }).when(session).send(any(Packet.class));
        doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; })
            .when(session).switchInboundState(any(Runnable.class));
        doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; })
            .when(session).switchOutboundState(any(Runnable.class));
        Method listenerMethod = type.getDeclaredMethod("listener");
        listenerMethod.setAccessible(true);
        SessionListener listener = (SessionListener) listenerMethod.invoke(state);
        listener.packetReceived(session, mock(ClientboundSelectKnownPacks.class));
        listener.packetReceived(session, mock(ClientboundSelectKnownPacks.class)); // No duplicate replies.
        listener.packetReceived(session, mock(ClientboundFinishConfigurationPacket.class));
        assertEquals(List.of(ServerboundClientInformationPacket.class, ServerboundSelectKnownPacks.class,
            ServerboundFinishConfigurationPacket.class), writes);
        assertEquals(ProtocolState.GAME, protocol.getInboundState());
        assertEquals(ProtocolState.GAME, protocol.getOutboundState());
        assertEquals(true, await.invoke(null, List.of(state), 1L));
        assertEquals(3, writes.size());
    }
}

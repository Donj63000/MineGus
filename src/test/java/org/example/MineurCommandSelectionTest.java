package org.example;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.example.mineur.MiningCursor;
import org.example.mineur.MiningSessionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MineurCommandSelectionTest {

    private ServerMock server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            MockBukkit.unmock();
        }
    }

    @Test
    void listAndSelectOperateOnTheChosenMiner() throws Exception {
        server = MockBukkit.mock();
        MinePlugin plugin = MockBukkit.load(MinePlugin.class);
        Mineur mineur = plugin.getMineur();

        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        List<String> messages = new ArrayList<>();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.hasPermission("mineplugin.mineur.use")).thenReturn(true);
        doAnswer(invocation -> {
            messages.add(invocation.getArgument(0));
            return null;
        }).when(player).sendMessage(org.mockito.ArgumentMatchers.anyString());

        MiningSessionState first = createState(playerId, 10, 64, 10);
        MiningSessionState second = createState(playerId, 30, 64, 30);

        @SuppressWarnings("unchecked")
        List<MiningSessionState> sessions = (List<MiningSessionState>) field(mineur, "sessions").get(mineur);
        sessions.clear();
        sessions.add(first);
        sessions.add(second);

        @SuppressWarnings("unchecked")
        Map<UUID, List<UUID>> ownerSessions = (Map<UUID, List<UUID>>) field(mineur, "ownerSessions").get(mineur);
        ownerSessions.clear();
        ownerSessions.put(playerId, new ArrayList<>(List.of(first.id, second.id)));

        @SuppressWarnings("unchecked")
        Map<UUID, UUID> selectedSessions = (Map<UUID, UUID>) field(mineur, "selectedSessions").get(mineur);
        selectedSessions.clear();

        mineur.onCommand(player, null, "mineur", new String[]{"list"});
        assertTrue(messages.stream().anyMatch(line -> line.contains("Mineur 1")));
        assertTrue(messages.stream().anyMatch(line -> line.contains("Mineur 2")));
        assertTrue(messages.stream().anyMatch(line -> line.contains("30, 64, 30")));

        messages.clear();
        mineur.onCommand(player, null, "mineur", new String[]{"select", "2"});
        assertTrue(messages.stream().anyMatch(line -> line.contains("Mineur 2 selectionne")));
        assertFalse(first.selected);
        assertTrue(second.selected);

        messages.clear();
        mineur.onCommand(player, null, "mineur", new String[]{"info"});
        assertTrue(messages.stream().anyMatch(line -> line.contains("Base : 30, 64, 30")));
    }

    @Test
    void createMineRequiresMinerJob() throws Exception {
        server = MockBukkit.mock();
        MinePlugin plugin = MockBukkit.load(MinePlugin.class);
        Mineur mineur = plugin.getMineur();

        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        List<String> messages = new ArrayList<>();
        when(player.getUniqueId()).thenReturn(playerId);
        doAnswer(invocation -> {
            messages.add(invocation.getArgument(0));
            return null;
        }).when(player).sendMessage(org.mockito.ArgumentMatchers.anyString());

        Method createMine = mineur.getClass().getDeclaredMethod("createMineFromSelection", Player.class);
        createMine.setAccessible(true);
        createMine.invoke(mineur, player);

        assertTrue(messages.stream().anyMatch(line -> line.contains("Tu dois avoir le metier")));
        assertTrue(messages.stream().anyMatch(line -> line.contains("/job mineur")));
    }

    private static Field field(Object target, String name) throws NoSuchFieldException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static MiningSessionState createState(UUID ownerId, int x, int y, int z) {
        MiningSessionState state = new MiningSessionState();
        state.worldUid = UUID.randomUUID();
        state.base = new Location(null, x, y, z);
        state.width = 4;
        state.length = 4;
        state.owner = ownerId;
        state.cursor = new MiningCursor(state.base, state.width, state.length);
        return state;
    }
}

package org.example.mineur;

import org.bukkit.Location;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MiningLoopTest {

    @Test
    void missingMinerTriggersFailureWithoutCompletingTheWorksite() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());

        MiningSessionState state = new MiningSessionState();
        state.base = new Location(null, 0, 64, 0);
        state.width = 2;
        state.length = 2;
        state.cursor = new MiningCursor(state.base, state.width, state.length);
        state.pendingCursor = state.cursor.copy();
        state.cursor.x = 1;

        MiningIterator iterator = mock(MiningIterator.class);
        Villager invalidMiner = mock(Villager.class);
        when(invalidMiner.isDead()).thenReturn(false);
        when(invalidMiner.isValid()).thenReturn(false);

        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);
        MiningLoop loop = new MiningLoop(
                plugin,
                state,
                iterator,
                new InventoryRouter(List.of()),
                invalidMiner,
                null,
                null,
                null,
                () -> completed.set(true),
                null,
                null,
                null,
                exception -> failed.set(true),
                true,
                false,
                1.0D
        );

        loop.run();

        assertTrue(state.paused);
        assertTrue(failed.get());
        assertFalse(completed.get());
        assertFalse(state.cursor.exhausted);
        assertTrue(state.pendingCursor == null);
        assertTrue(state.cursor.x == 0);
    }


    @Test
    void completedIteratorDoesNotRequireAStorageContainer() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());

        MiningSessionState state = new MiningSessionState();
        state.base = new Location(null, 0, 64, 0);
        state.width = 1;
        state.length = 1;
        state.cursor = new MiningCursor(state.base, 1, 1);

        MiningIterator iterator = mock(MiningIterator.class);
        when(iterator.hasNext()).thenReturn(false);

        Villager miner = mock(Villager.class);
        when(miner.isDead()).thenReturn(false);
        when(miner.isValid()).thenReturn(true);

        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicBoolean storageBlocked = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);
        MiningLoop loop = new MiningLoop(
                plugin,
                state,
                iterator,
                new InventoryRouter(List.of()),
                miner,
                null,
                null,
                null,
                () -> completed.set(true),
                () -> storageBlocked.set(true),
                null,
                null,
                exception -> failed.set(true),
                true,
                false,
                1.0D
        );

        loop.run();

        assertTrue(completed.get());
        assertFalse(storageBlocked.get());
        assertFalse(failed.get());
    }

    @Test
    void depositingPhaseFinishesEvenWhenStorageBecameFull() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());

        MiningSessionState state = new MiningSessionState();
        state.base = new Location(null, 0, 64, 0);
        state.width = 1;
        state.length = 1;
        state.cursor = new MiningCursor(state.base, 1, 1);

        Villager miner = mock(Villager.class);
        when(miner.isDead()).thenReturn(false);
        when(miner.isValid()).thenReturn(true);

        AtomicBoolean storageBlocked = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);
        MiningLoop loop = new MiningLoop(
                plugin,
                state,
                mock(MiningIterator.class),
                new InventoryRouter(List.of()),
                miner,
                null,
                null,
                null,
                null,
                () -> storageBlocked.set(true),
                null,
                null,
                exception -> failed.set(true),
                true,
                false,
                1.0D
        );

        /*
         * La phase est positionnée après une casse réussie : le dépôt a déjà eu
         * lieu, mais le coffre vient d'être rempli. Aucun contrôle de capacité
         * ne doit empêcher la remise à IDLE.
         */
        java.lang.reflect.Field phase = MiningLoop.class.getDeclaredField("phase");
        java.lang.reflect.Field current = MiningLoop.class.getDeclaredField("current");
        phase.setAccessible(true);
        current.setAccessible(true);
        phase.set(loop, MiningLoop.Phase.DEPOSITING);
        current.set(loop, mock(org.bukkit.block.Block.class));

        loop.run();

        assertEquals(MiningLoop.Phase.IDLE, phase.get(loop));
        assertNull(current.get(loop));
        assertFalse(storageBlocked.get());
        assertFalse(failed.get());
    }

}

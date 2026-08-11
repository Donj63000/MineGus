package org.example.mineur;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.java.JavaPlugin;
import org.example.TeleportUtils;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MiningLoopTest {

    @Test
    void preparationCheckBlocksProgressUntilTheSectionIsReady() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());

        World world = mock(World.class);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);

        Block safeSpace = mock(Block.class);
        when(safeSpace.isPassable()).thenReturn(true);
        when(safeSpace.isLiquid()).thenReturn(false);
        when(safeSpace.getType()).thenReturn(Material.AIR);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(safeSpace);

        Block minedBlock = mock(Block.class);
        when(minedBlock.getWorld()).thenReturn(world);
        when(minedBlock.getX()).thenReturn(0);
        when(minedBlock.getY()).thenReturn(64);
        when(minedBlock.getZ()).thenReturn(0);
        when(minedBlock.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(minedBlock.getType()).thenReturn(Material.STONE);
        when(minedBlock.isLiquid()).thenReturn(false);
        when(minedBlock.getState()).thenReturn(mock(BlockState.class));
        when(minedBlock.getBlockData()).thenReturn(mock(BlockData.class));
        when(minedBlock.getDrops()).thenReturn(List.of());

        MiningSessionState state = new MiningSessionState();
        state.base = new Location(world, 0, 64, 0);
        state.width = 1;
        state.length = 1;
        state.cursor = new MiningCursor(state.base, 1, 1);

        MiningIterator iterator = mock(MiningIterator.class);
        when(iterator.hasNext()).thenReturn(true);
        when(iterator.cursor()).thenReturn(state.cursor);
        when(iterator.next()).thenReturn(minedBlock);

        InventoryRouter router = mock(InventoryRouter.class);
        when(router.hasTargets()).thenReturn(true);
        when(router.canFitAll(anyList())).thenReturn(true);

        Villager miner = mock(Villager.class);
        when(miner.isDead()).thenReturn(false);
        when(miner.isValid()).thenReturn(true);
        when(miner.getLocation()).thenReturn(new Location(world, 0.5D, 64.0D, 0.5D));

        AtomicBoolean preparationReady = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);
        MiningLoop loop = new MiningLoop(
                plugin,
                state,
                iterator,
                router,
                miner,
                null,
                null,
                ignored -> MiningLoop.BreakPermissionResult.ALLOWED,
                null,
                null,
                null,
                null,
                null,
                ignored -> failed.set(true),
                null,
                true,
                false,
                2.0D,
                preparationReady::get
        );

        loop.run();

        verify(iterator, never()).hasNext();
        verify(iterator, never()).next();
        verify(minedBlock, never()).setType(Material.AIR, true);
        assertNull(state.pendingCursor);
        assertFalse(failed.get());

        preparationReady.set(true);
        try (MockedStatic<TeleportUtils> teleportUtils = mockStatic(TeleportUtils.class)) {
            teleportUtils.when(() -> TeleportUtils.safeTeleport(
                    eq(miner),
                    any(Location.class)
            )).thenReturn(true);

            /*
             * Je laisse la machine reprendre normalement : sélection du bloc,
             * animation, puis casse au lieu de forcer son état par réflexion.
             */
            loop.run();
            loop.run();
            loop.run();
        }

        verify(iterator).hasNext();
        verify(iterator).next();
        verify(minedBlock).setType(Material.AIR, true);
        assertNull(state.pendingCursor);
        assertFalse(state.paused);
        assertFalse(failed.get());
    }

    @Test
    void missingMinerRequestsRecoveryWithoutPausingOrCompletingTheWorksite() {
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
        AtomicBoolean recoveryRequested = new AtomicBoolean(false);
        MiningLoop loop = new MiningLoop(
                plugin,
                state,
                iterator,
                new InventoryRouter(List.of()),
                invalidMiner,
                null,
                null,
                null,
                null,
                () -> completed.set(true),
                null,
                null,
                null,
                exception -> failed.set(true),
                () -> recoveryRequested.set(true),
                true,
                false,
                1.0D
        );

        loop.run();

        assertFalse(state.paused);
        assertFalse(failed.get());
        assertTrue(recoveryRequested.get());
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
                null,
                () -> completed.set(true),
                () -> storageBlocked.set(true),
                null,
                null,
                exception -> failed.set(true),
                null,
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
    void managedShaftBlockAdvancesTheCheckpointWithoutBreakingIt() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());

        MiningSessionState state = new MiningSessionState();
        state.base = new Location(null, 0, 64, 0);
        state.width = 1;
        state.length = 1;
        state.cursor = new MiningCursor(state.base, 1, 1);

        MiningIterator iterator = mock(MiningIterator.class);
        when(iterator.hasNext()).thenReturn(true);
        when(iterator.cursor()).thenReturn(state.cursor);

        Block managedBlock = mock(Block.class);
        when(managedBlock.getType()).thenReturn(Material.STRIPPED_DARK_OAK_LOG);
        when(managedBlock.isLiquid()).thenReturn(false);
        when(managedBlock.getState()).thenReturn(mock(BlockState.class));
        when(iterator.next()).thenReturn(managedBlock);

        InventoryRouter router = mock(InventoryRouter.class);
        when(router.hasTargets()).thenReturn(true);
        when(router.canFitAll(anyList())).thenReturn(true);

        Villager miner = mock(Villager.class);
        when(miner.isDead()).thenReturn(false);
        when(miner.isValid()).thenReturn(true);

        AtomicBoolean breakPermissionChecked = new AtomicBoolean(false);
        AtomicBoolean decorated = new AtomicBoolean(false);
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);

        MiningLoop loop = new MiningLoop(
                plugin,
                state,
                iterator,
                router,
                miner,
                null,
                block -> false,
                block -> {
                    breakPermissionChecked.set(true);
                    return MiningLoop.BreakPermissionResult.ALLOWED;
                },
                block -> decorated.set(true),
                () -> completed.set(true),
                null,
                null,
                null,
                exception -> failed.set(true),
                null,
                true,
                false,
                1.0D
        );

        loop.run();

        /*
         * Le bloc technique a bien été consommé par l'itérateur, mais il reste
         * intact : le filtre intervient avant le calcul des drops, l'animation,
         * les protections de casse et le callback de décoration.
         */
        assertNull(state.pendingCursor);
        assertFalse(state.paused);
        assertFalse(breakPermissionChecked.get());
        assertFalse(decorated.get());
        assertFalse(completed.get());
        assertFalse(failed.get());
    }

    @Test
    void temporaryPermissionFailureWaitsWithoutPausingOrLosingTheCheckpoint() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());

        MiningSessionState state = new MiningSessionState();
        state.base = new Location(null, 0, 64, 0);
        state.width = 1;
        state.length = 1;
        state.cursor = new MiningCursor(state.base, 1, 1);
        state.pendingCursor = state.cursor.copy();

        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.STONE);
        when(block.isLiquid()).thenReturn(false);
        when(block.getState()).thenReturn(mock(BlockState.class));
        when(block.getDrops()).thenReturn(List.of());

        InventoryRouter router = mock(InventoryRouter.class);
        when(router.hasTargets()).thenReturn(true);
        when(router.canFitAll(anyList())).thenReturn(true);

        Villager miner = mock(Villager.class);
        when(miner.isDead()).thenReturn(false);
        when(miner.isValid()).thenReturn(true);

        AtomicInteger permissionChecks = new AtomicInteger();
        AtomicBoolean protectionBlocked = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);
        MiningLoop loop = new MiningLoop(
                plugin,
                state,
                mock(MiningIterator.class),
                router,
                miner,
                null,
                null,
                ignored -> {
                    permissionChecks.incrementAndGet();
                    return MiningLoop.BreakPermissionResult.RETRY_LATER;
                },
                null,
                null,
                null,
                null,
                ignored -> protectionBlocked.set(true),
                ignored -> failed.set(true),
                null,
                true,
                false,
                1.0D
        );

        setField(loop, "phase", MiningLoop.Phase.BREAKING);
        setField(loop, "current", block);
        setField(loop, "currentType", Material.STONE);
        setField(loop, "currentDrops", List.of());
        setField(loop, "phaseProgress", 1.0D);

        loop.run();

        assertEquals(1, permissionChecks.get());
        assertFalse(state.paused);
        assertTrue(state.pendingCursor != null);
        assertFalse(protectionBlocked.get());
        assertFalse(failed.get());
        assertEquals(MiningLoop.Phase.BREAKING, field(loop, "phase"));
        verify(block, never()).setType(Material.AIR, true);
    }

    @Test
    void definitivePermissionRefusalStillPausesAndRollsBackSafely() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());

        MiningSessionState state = new MiningSessionState();
        state.base = new Location(null, 0, 64, 0);
        state.width = 2;
        state.length = 1;
        state.cursor = new MiningCursor(state.base, 2, 1);
        state.pendingCursor = state.cursor.copy();
        state.cursor.x = 1;

        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.STONE);
        when(block.isLiquid()).thenReturn(false);
        when(block.getState()).thenReturn(mock(BlockState.class));
        when(block.getDrops()).thenReturn(List.of());

        InventoryRouter router = mock(InventoryRouter.class);
        when(router.hasTargets()).thenReturn(true);
        when(router.canFitAll(anyList())).thenReturn(true);

        Villager miner = mock(Villager.class);
        when(miner.isDead()).thenReturn(false);
        when(miner.isValid()).thenReturn(true);

        AtomicBoolean protectionBlocked = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);
        MiningLoop loop = new MiningLoop(
                plugin,
                state,
                mock(MiningIterator.class),
                router,
                miner,
                null,
                null,
                ignored -> MiningLoop.BreakPermissionResult.DENIED,
                null,
                null,
                null,
                null,
                ignored -> protectionBlocked.set(true),
                ignored -> failed.set(true),
                null,
                true,
                false,
                1.0D
        );

        setField(loop, "phase", MiningLoop.Phase.BREAKING);
        setField(loop, "current", block);
        setField(loop, "currentType", Material.STONE);
        setField(loop, "currentDrops", List.of());
        setField(loop, "phaseProgress", 1.0D);

        loop.run();

        assertTrue(state.paused);
        assertEquals(MiningSessionState.PauseReason.PROTECTION, state.pauseReason);
        assertTrue(protectionBlocked.get());
        assertFalse(failed.get());
        assertNull(state.pendingCursor);
        assertEquals(0, state.cursor.x);
        verify(block, never()).setType(Material.AIR, true);
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
                null,
                () -> storageBlocked.set(true),
                null,
                null,
                exception -> failed.set(true),
                null,
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

    private static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object field(Object target, String name) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

}

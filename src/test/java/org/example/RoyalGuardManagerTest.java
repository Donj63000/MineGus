package org.example;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoyalGuardManagerTest {

    private ServerMock server;
    private MinePlugin plugin;
    private RoyalGuardManager manager;
    private GuardFactoryStub guardFactory;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(MinePlugin.class);
        guardFactory = new GuardFactoryStub();
        // Ici, je remplace les appels Paper non simulés par MockBukkit dans les tests unitaires.
        manager = new RoyalGuardManager(plugin, (guard, target, speed) -> true, false, guardFactory,
                (owner, slot) -> owner.getLocation().clone().add(slot == 0 ? 2.0D : -2.0D, 0.0D, 0.0D));
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.shutdown();
        }
        if (server != null) {
            MockBukkit.unmock();
        }
    }

    @Test
    void firstCommandCreatesExactlyTwoTaggedRoyalGuardsWithDefaultSettings() {
        GuardFixture fixture = new GuardFixture();

        manager.onCommand(fixture.owner, null, "garde", new String[0]);

        assertTrue(manager.hasActiveSquad(fixture.ownerId));
        assertEquals(2, manager.activeGuardCount(fixture.ownerId));
        assertEquals(2, guardFactory.requests.size());
        assertSpawnRequest(guardFactory.requests.get(0), fixture.ownerId, 0);
        assertSpawnRequest(guardFactory.requests.get(1), fixture.ownerId, 1);
        assertTrue(manager.isRoyalGuard(fixture.first.guard));
        assertEquals(fixture.ownerId, manager.ownerOf(fixture.first.guard));
        assertEquals(0, manager.slotOf(fixture.first.guard));
    }

    @Test
    void royalLoadoutSpecificationKeepsTheRequestedNetheriteAndEnchantmentLevels() {
        assertEquals(Material.NETHERITE_HELMET, RoyalGuardManager.ROYAL_HELMET);
        assertEquals(Material.NETHERITE_CHESTPLATE, RoyalGuardManager.ROYAL_CHESTPLATE);
        assertEquals(Material.NETHERITE_LEGGINGS, RoyalGuardManager.ROYAL_LEGGINGS);
        assertEquals(Material.NETHERITE_BOOTS, RoyalGuardManager.ROYAL_BOOTS);
        assertEquals(Material.NETHERITE_SWORD, RoyalGuardManager.ROYAL_SWORD);
        assertEquals(4, RoyalGuardManager.ARMOR_PROTECTION_LEVEL);
        assertEquals(3, RoyalGuardManager.ARMOR_UNBREAKING_LEVEL);
        assertEquals(1, RoyalGuardManager.ARMOR_MENDING_LEVEL);
        assertEquals(5, RoyalGuardManager.SWORD_SHARPNESS_LEVEL);
        assertEquals(3, RoyalGuardManager.SWORD_UNBREAKING_LEVEL);
        assertEquals(1, RoyalGuardManager.SWORD_MENDING_LEVEL);
    }

    @Test
    void invalidAttributeConfigurationFallsBackToSafeDefaults() {
        plugin.getConfig().set("garde.attributes.max-health", Double.POSITIVE_INFINITY);
        plugin.getConfig().set("garde.attributes.attack-damage", 4096.0D);
        plugin.getConfig().set("garde.attributes.movement-speed", -1.0D);
        plugin.getConfig().set("garde.attributes.knockback-resistance", 2.0D);

        RoyalGuardManager.GuardSettings settings = RoyalGuardManager.GuardSettings.from(plugin);

        assertEquals(100.0D, settings.maxHealth());
        assertEquals(16.0D, settings.attackDamage());
        assertEquals(0.35D, settings.movementSpeed());
        assertEquals(0.6D, settings.knockbackResistance());
    }

    @Test
    void ownerDamageHandlerObservesTheFinalCancellationState() throws NoSuchMethodException {
        EventHandler handler = RoyalGuardManager.class
                .getMethod("onOwnerDamaged", EntityDamageByEntityEvent.class)
                .getAnnotation(EventHandler.class);

        assertEquals(EventPriority.MONITOR, handler.priority());
        assertTrue(handler.ignoreCancelled());
    }

    @Test
    void secondCommandDismissesExistingPairWithoutSpawningDuplicates() {
        GuardFixture fixture = new GuardFixture();

        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        manager.onCommand(fixture.owner, null, "garde", new String[0]);

        assertFalse(manager.hasActiveSquad(fixture.ownerId));
        assertEquals(0, manager.activeGuardCount(fixture.ownerId));
        assertEquals(2, guardFactory.requests.size());
        verify(fixture.first.guard).remove();
        verify(fixture.second.guard).remove();
    }

    @Test
    void failedInitialSpawnRollsBackThePartialDuoAndReportsTheFailure() {
        GuardFixture fixture = new GuardFixture();
        guardFactory.clearAvailable();
        guardFactory.add(fixture.first.guard, null);

        manager.onCommand(fixture.owner, null, "garde", new String[0]);

        assertFalse(manager.hasActiveSquad(fixture.ownerId));
        assertEquals(0, manager.activeGuardCount(fixture.ownerId));
        assertEquals(2, guardFactory.requests.size());
        verify(fixture.first.guard).remove();
        verify(fixture.owner).sendMessage(ChatColor.RED
                + "Impossible d'invoquer tes deux gardes royaux. Réessaie dans un instant.");
    }

    @Test
    void damageTargetsOnlyTheActualUncancelledAttacker() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);

        LivingEntity attacker = livingEntity(fixture.world);
        EntityDamageByEntityEvent cancelled = mock(EntityDamageByEntityEvent.class);
        when(cancelled.isCancelled()).thenReturn(true);
        manager.onOwnerDamaged(cancelled);
        verify(fixture.first.guard, never()).setTarget(any(LivingEntity.class));

        EntityDamageByEntityEvent attack = mock(EntityDamageByEntityEvent.class);
        when(attack.isCancelled()).thenReturn(false);
        when(attack.getEntity()).thenReturn(fixture.owner);
        when(attack.getDamager()).thenReturn(attacker);
        manager.onOwnerDamaged(attack);

        verify(fixture.first.guard).setTarget(attacker);
        verify(fixture.second.guard).setTarget(attacker);

        Player projectileAttacker = mock(Player.class);
        when(projectileAttacker.getUniqueId()).thenReturn(UUID.randomUUID());
        when(projectileAttacker.getWorld()).thenReturn(fixture.world);
        when(projectileAttacker.getLocation()).thenReturn(new Location(fixture.world, 1.0D, 64.0D, 0.0D));
        when(projectileAttacker.isValid()).thenReturn(true);
        when(projectileAttacker.isDead()).thenReturn(false);
        Projectile projectile = mock(Projectile.class);
        when(projectile.getShooter()).thenReturn(projectileAttacker);
        EntityDamageByEntityEvent projectileAttack = mock(EntityDamageByEntityEvent.class);
        when(projectileAttack.isCancelled()).thenReturn(false);
        when(projectileAttack.getEntity()).thenReturn(fixture.owner);
        when(projectileAttack.getDamager()).thenReturn(projectile);
        manager.onOwnerDamaged(projectileAttack);
        verify(fixture.first.guard).setTarget(projectileAttacker);
        verify(fixture.second.guard).setTarget(projectileAttacker);

        EntityTargetLivingEntityEvent ownerTarget = mock(EntityTargetLivingEntityEvent.class);
        when(ownerTarget.getEntity()).thenReturn(fixture.first.guard);
        when(ownerTarget.getTarget()).thenReturn(fixture.owner);
        manager.onGuardTargets(ownerTarget);
        verify(ownerTarget).setCancelled(true);

        EntityTargetLivingEntityEvent siblingTarget = mock(EntityTargetLivingEntityEvent.class);
        when(siblingTarget.getEntity()).thenReturn(fixture.first.guard);
        when(siblingTarget.getTarget()).thenReturn(fixture.second.guard);
        manager.onGuardTargets(siblingTarget);
        verify(siblingTarget).setCancelled(true);

        EntityTargetLivingEntityEvent unrelatedTarget = mock(EntityTargetLivingEntityEvent.class);
        LivingEntity unrelatedTargetEntity = livingEntity(fixture.world);
        when(unrelatedTarget.getEntity()).thenReturn(fixture.first.guard);
        when(unrelatedTarget.getTarget()).thenReturn(unrelatedTargetEntity);
        manager.onGuardTargets(unrelatedTarget);
        verify(unrelatedTarget).setCancelled(true);

        EntityTargetLivingEntityEvent realThreat = mock(EntityTargetLivingEntityEvent.class);
        when(realThreat.getEntity()).thenReturn(fixture.first.guard);
        when(realThreat.getTarget()).thenReturn(projectileAttacker);
        manager.onGuardTargets(realThreat);
        verify(realThreat, never()).setCancelled(true);

        EntityDamageByEntityEvent friendlyFire = mock(EntityDamageByEntityEvent.class);
        when(friendlyFire.isCancelled()).thenReturn(false);
        when(friendlyFire.getDamager()).thenReturn(fixture.first.guard);
        when(friendlyFire.getEntity()).thenReturn(fixture.owner);
        manager.onGuardDamages(friendlyFire);
        verify(friendlyFire).setCancelled(true);

        EntityDamageByEntityEvent siblingFire = mock(EntityDamageByEntityEvent.class);
        when(siblingFire.isCancelled()).thenReturn(false);
        when(siblingFire.getDamager()).thenReturn(fixture.first.guard);
        when(siblingFire.getEntity()).thenReturn(fixture.second.guard);
        manager.onGuardDamages(siblingFire);
        verify(siblingFire).setCancelled(true);

        EntityDamageByEntityEvent unrelatedFire = mock(EntityDamageByEntityEvent.class);
        LivingEntity unrelatedDamageTarget = livingEntity(fixture.world);
        when(unrelatedFire.isCancelled()).thenReturn(false);
        when(unrelatedFire.getDamager()).thenReturn(fixture.first.guard);
        when(unrelatedFire.getEntity()).thenReturn(unrelatedDamageTarget);
        manager.onGuardDamages(unrelatedFire);
        verify(unrelatedFire).setCancelled(true);
    }

    @Test
    void guardFromAnotherOwnerIsHandledAsARealAttackerButTheTwinIsIgnored() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);

        Husk foreignGuard = guard(fixture.world, UUID.randomUUID(), 0).guard;
        EntityDamageByEntityEvent foreignAttack = mock(EntityDamageByEntityEvent.class);
        when(foreignAttack.isCancelled()).thenReturn(false);
        when(foreignAttack.getEntity()).thenReturn(fixture.owner);
        when(foreignAttack.getDamager()).thenReturn(foreignGuard);
        manager.onOwnerDamaged(foreignAttack);

        verify(fixture.first.guard).setTarget(foreignGuard);
        verify(fixture.second.guard).setTarget(foreignGuard);

        GuardFixture siblingFixture = new GuardFixture();
        manager.onCommand(siblingFixture.owner, null, "garde", new String[0]);
        EntityDamageByEntityEvent siblingAttack = mock(EntityDamageByEntityEvent.class);
        when(siblingAttack.isCancelled()).thenReturn(false);
        when(siblingAttack.getEntity()).thenReturn(siblingFixture.owner);
        when(siblingAttack.getDamager()).thenReturn(siblingFixture.second.guard);
        manager.onOwnerDamaged(siblingAttack);

        verify(siblingFixture.first.guard, never()).setTarget(any(LivingEntity.class));
        verify(siblingFixture.second.guard, never()).setTarget(any(LivingEntity.class));
    }

    @Test
    void guardDeathClearsLootAndRespawnsOnlyTheMissingSlotAfterTwentySeconds() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);

        List<ItemStack> drops = new ArrayList<>();
        drops.add(mock(ItemStack.class));
        EntityDeathEvent death = deathOf(fixture.first.guard, drops);
        manager.onGuardDeath(death);

        assertTrue(drops.isEmpty());
        assertEquals(1, manager.activeGuardCount(fixture.ownerId));
        verify(death).setDroppedExp(0);

        server.getScheduler().performTicks(399);
        assertEquals(2, guardFactory.requests.size());
        server.getScheduler().performTicks(1);

        assertEquals(3, guardFactory.requests.size());
        assertEquals(2, manager.activeGuardCount(fixture.ownerId));
        assertEquals(0, guardFactory.requests.get(2).slot());
    }

    @Test
    void dismissalAndDisconnectCancelPendingRespawns() {
        GuardFixture dismissed = new GuardFixture();
        manager.onCommand(dismissed.owner, null, "garde", new String[0]);
        manager.onGuardDeath(deathOf(dismissed.first.guard));
        manager.onCommand(dismissed.owner, null, "garde", new String[0]);
        server.getScheduler().performTicks(400);
        assertEquals(2, guardFactory.requests.size());

        guardFactory.clearAvailable();
        GuardFixture disconnected = new GuardFixture();
        manager.onCommand(disconnected.owner, null, "garde", new String[0]);
        manager.onGuardDeath(deathOf(disconnected.first.guard));
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(disconnected.owner);
        manager.onPlayerQuit(quit);
        server.getScheduler().performTicks(400);
        assertEquals(4, guardFactory.requests.size());
        assertFalse(manager.hasActiveSquad(disconnected.ownerId));
    }

    @Test
    void worldChangeRecallsBothGuardsNearTheirOwner() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);

        PlayerChangedWorldEvent event = mock(PlayerChangedWorldEvent.class);
        when(event.getPlayer()).thenReturn(fixture.owner);
        manager.onPlayerChangedWorld(event);

        verify(fixture.first.guard).teleport(any(Location.class));
        verify(fixture.second.guard).teleport(any(Location.class));
        verify(fixture.first.guard).setFallDistance(0.0F);
        verify(fixture.second.guard).setFallDistance(0.0F);
    }

    @Test
    void guardBeyondFollowRadiusIsRecalledDuringTheFollowCheck() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        when(fixture.first.guard.getLocation()).thenReturn(new Location(fixture.world, 30.0D, 64.0D, 0.0D));

        manager.followActiveSquads();

        verify(fixture.first.guard).teleport(any(Location.class));
        verify(fixture.first.guard).setFallDistance(0.0F);
    }

    @Test
    void recallingAGuardKeepsItsCurrentThreat() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        LivingEntity attacker = livingEntity(fixture.world);
        EntityDamageByEntityEvent attack = mock(EntityDamageByEntityEvent.class);
        when(attack.isCancelled()).thenReturn(false);
        when(attack.getEntity()).thenReturn(fixture.owner);
        when(attack.getDamager()).thenReturn(attacker);
        manager.onOwnerDamaged(attack);
        when(fixture.first.guard.getTarget()).thenReturn(attacker);
        when(fixture.first.guard.getLocation()).thenReturn(new Location(fixture.world, 30.0D, 64.0D, 0.0D));

        manager.followActiveSquads();

        verify(fixture.first.guard).teleport(any(Location.class));
        verify(fixture.first.guard, never()).setTarget((LivingEntity) null);
    }

    @Test
    void threatThatLeavesTheOwnersWorldIsClearedBeforeFollowingResumes() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        LivingEntity attacker = livingEntity(fixture.world);
        EntityDamageByEntityEvent attack = mock(EntityDamageByEntityEvent.class);
        when(attack.isCancelled()).thenReturn(false);
        when(attack.getEntity()).thenReturn(fixture.owner);
        when(attack.getDamager()).thenReturn(attacker);
        manager.onOwnerDamaged(attack);
        when(attacker.getWorld()).thenReturn(mock(World.class));

        manager.followActiveSquads();

        verify(fixture.first.guard).setTarget((LivingEntity) null);
        verify(fixture.second.guard).setTarget((LivingEntity) null);
    }

    @Test
    void guardIsRecalledOnTheThirdConsecutiveStuckCheck() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        when(fixture.first.guard.getLocation()).thenReturn(new Location(fixture.world, 10.0D, 64.0D, 0.0D));

        manager.followActiveSquads();
        manager.followActiveSquads();
        manager.followActiveSquads();

        verify(fixture.first.guard, times(1)).teleport(any(Location.class));
    }

    @Test
    void persistedUntrackedGuardIsRemovedWhenItsChunkLoadsButCurrentGuardIsKept() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        Husk orphan = guard(fixture.world, UUID.randomUUID(), 0).guard;
        EntitiesLoadEvent event = mock(EntitiesLoadEvent.class);
        when(event.getEntities()).thenReturn(List.<Entity>of(fixture.first.guard, orphan));

        manager.onEntitiesLoad(event);

        verify(orphan).remove();
        verify(fixture.first.guard, never()).remove();
    }

    @Test
    void invalidGuardIdentityIsNeverAcceptedAsARoyalGuard() {
        Husk noOwner = mock(Husk.class);
        PersistentDataContainer noOwnerPdc = mock(PersistentDataContainer.class);
        when(noOwner.getPersistentDataContainer()).thenReturn(noOwnerPdc);
        when(noOwnerPdc.get(Keys.royalGuardType(), PersistentDataType.STRING)).thenReturn(RoyalGuardManager.GUARD_TYPE);
        when(noOwnerPdc.get(Keys.royalGuardSlot(), PersistentDataType.INTEGER)).thenReturn(0);

        Husk invalidSlot = mock(Husk.class);
        PersistentDataContainer invalidSlotPdc = mock(PersistentDataContainer.class);
        when(invalidSlot.getPersistentDataContainer()).thenReturn(invalidSlotPdc);
        when(invalidSlotPdc.get(Keys.royalGuardType(), PersistentDataType.STRING)).thenReturn(RoyalGuardManager.GUARD_TYPE);
        when(invalidSlotPdc.get(Keys.royalGuardOwner(), PersistentDataType.STRING)).thenReturn(UUID.randomUUID().toString());
        when(invalidSlotPdc.get(Keys.royalGuardSlot(), PersistentDataType.INTEGER)).thenReturn(2);

        assertFalse(manager.isRoyalGuard(noOwner));
        assertFalse(manager.isRoyalGuard(invalidSlot));
    }

    private void assertSpawnRequest(SpawnRequest request, UUID ownerId, int slot) {
        assertEquals(ownerId, request.ownerId());
        assertEquals(slot, request.slot());
        assertEquals(100.0D, request.settings().maxHealth());
        assertEquals(16.0D, request.settings().attackDamage());
        assertEquals(0.35D, request.settings().movementSpeed());
        assertEquals(0.6D, request.settings().knockbackResistance());
    }

    private EntityDeathEvent deathOf(Husk guard) {
        return deathOf(guard, new ArrayList<>());
    }

    private EntityDeathEvent deathOf(Husk guard, List<ItemStack> drops) {
        EntityDeathEvent death = mock(EntityDeathEvent.class);
        when(death.getEntity()).thenReturn(guard);
        when(death.getDrops()).thenReturn(drops);
        return death;
    }

    private LivingEntity livingEntity(World world) {
        LivingEntity entity = mock(LivingEntity.class);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.getWorld()).thenReturn(world);
        when(entity.getLocation()).thenReturn(new Location(world, 1.0D, 64.0D, 0.0D));
        when(entity.isValid()).thenReturn(true);
        when(entity.isDead()).thenReturn(false);
        return entity;
    }

    private final class GuardFixture {
        private final UUID ownerId = UUID.randomUUID();
        private final World world = mock(World.class);
        private final Player owner = mock(Player.class);
        private final GuardDouble first = guard(world, ownerId, 0);
        private final GuardDouble second = guard(world, ownerId, 1);
        private final GuardDouble respawn = guard(world, ownerId, 0);

        private GuardFixture() {
            Location location = new Location(world, 0.0D, 64.0D, 0.0D);
            when(owner.getUniqueId()).thenReturn(ownerId);
            when(owner.hasPermission("mineplugin.garde.use")).thenReturn(true);
            when(owner.isOnline()).thenReturn(true);
            when(owner.getWorld()).thenReturn(world);
            when(owner.getLocation()).thenReturn(location);

            guardFactory.add(first.guard, second.guard, respawn.guard);
        }
    }

    private GuardDouble guard(World world, UUID ownerId, int slot) {
        Husk guard = mock(Husk.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(guard.getUniqueId()).thenReturn(UUID.randomUUID());
        when(guard.getWorld()).thenReturn(world);
        when(guard.getLocation()).thenReturn(new Location(world, 2.0D, 64.0D, 0.0D));
        when(guard.isValid()).thenReturn(true);
        when(guard.isDead()).thenReturn(false);
        when(guard.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(Keys.royalGuardType(), PersistentDataType.STRING)).thenReturn(RoyalGuardManager.GUARD_TYPE);
        when(pdc.get(Keys.royalGuardOwner(), PersistentDataType.STRING)).thenReturn(ownerId.toString());
        when(pdc.get(Keys.royalGuardSlot(), PersistentDataType.INTEGER)).thenReturn(slot);
        return new GuardDouble(guard);
    }

    private static final class GuardFactoryStub implements RoyalGuardManager.GuardFactory {
        private final Deque<Husk> guards = new LinkedList<>();
        private final List<SpawnRequest> requests = new ArrayList<>();

        private void add(Husk... guards) {
            for (Husk guard : guards) {
                this.guards.addLast(guard);
            }
        }

        private void clearAvailable() {
            guards.clear();
        }

        @Override
        public Husk spawn(Player owner, Location location, UUID ownerId, int slot,
                          RoyalGuardManager.GuardSettings settings) {
            requests.add(new SpawnRequest(ownerId, slot, settings));
            return guards.removeFirst();
        }
    }

    private record SpawnRequest(UUID ownerId, int slot, RoyalGuardManager.GuardSettings settings) {
    }

    private record GuardDouble(Husk guard) {
    }
}

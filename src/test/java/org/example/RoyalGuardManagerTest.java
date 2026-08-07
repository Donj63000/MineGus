package org.example;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import org.bukkit.ChatColor;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.ComplexLivingEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowman;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.Zombie;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
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

        ItemStack armor = RoyalGuardManager.createRoyalArmor(Material.NETHERITE_CHESTPLATE);
        ItemStack sword = RoyalGuardManager.createRoyalSword();
        assertNotNull(armor.getItemMeta());
        assertNotNull(sword.getItemMeta());
        assertTrue(armor.getItemMeta().isUnbreakable());
        assertTrue(sword.getItemMeta().isUnbreakable());
    }

    @Test
    void invalidAttributeConfigurationFallsBackToSafeDefaults() {
        plugin.getConfig().set("garde.attributes.max-health", Double.POSITIVE_INFINITY);
        plugin.getConfig().set("garde.attributes.attack-damage", 4096.0D);
        plugin.getConfig().set("garde.attributes.movement-speed", -1.0D);
        plugin.getConfig().set("garde.attributes.follow-range", 512.0D);
        plugin.getConfig().set("garde.attributes.knockback-resistance", 2.0D);

        RoyalGuardManager.GuardSettings settings = RoyalGuardManager.GuardSettings.from(plugin);

        assertEquals(100.0D, settings.maxHealth());
        assertEquals(16.0D, settings.attackDamage());
        assertEquals(0.35D, settings.movementSpeed());
        assertEquals(48.0D, settings.followRange());
        assertEquals(0.6D, settings.knockbackResistance());
    }

    @Test
    void followRangeCannotBeLowerThanProtectionOrTheCombatLeash() {
        plugin.getConfig().set("garde.protection-radius", 64.0D);
        plugin.getConfig().set("garde.combat-leash-radius", 72.0D);
        plugin.getConfig().set("garde.attributes.follow-range", 20.0D);

        RoyalGuardManager.GuardSettings settings = RoyalGuardManager.GuardSettings.from(plugin);

        assertEquals(72.0D, settings.followRange());
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
    void offensiveAssistAndProactiveDetectionObserveTheFinalEventState() throws NoSuchMethodException {
        EventHandler attackHandler = RoyalGuardManager.class
                .getMethod("onOwnerAttacks", EntityDamageByEntityEvent.class)
                .getAnnotation(EventHandler.class);
        EventHandler targetHandler = RoyalGuardManager.class
                .getMethod("onEntityTargetsOwner", EntityTargetLivingEntityEvent.class)
                .getAnnotation(EventHandler.class);

        assertEquals(EventPriority.MONITOR, attackHandler.priority());
        assertTrue(attackHandler.ignoreCancelled());
        assertEquals(EventPriority.MONITOR, targetHandler.priority());
        assertTrue(targetHandler.ignoreCancelled());
    }

    @Test
    void golemProtectionHandlersRunLastAndObserveAlreadyCancelledEvents() throws NoSuchMethodException {
        EventHandler ironTargetHandler = RoyalGuardManager.class
                .getMethod("onIronGolemTargetsGuard", EntityTargetLivingEntityEvent.class)
                .getAnnotation(EventHandler.class);
        EventHandler ironDamageHandler = RoyalGuardManager.class
                .getMethod("onIronGolemDamagesGuard", EntityDamageByEntityEvent.class)
                .getAnnotation(EventHandler.class);
        EventHandler snowTargetHandler = RoyalGuardManager.class
                .getMethod("onSnowGolemTargetsGuard", EntityTargetLivingEntityEvent.class)
                .getAnnotation(EventHandler.class);
        EventHandler snowDamageHandler = RoyalGuardManager.class
                .getMethod("onSnowGolemDamagesGuard", EntityDamageByEntityEvent.class)
                .getAnnotation(EventHandler.class);

        assertEquals(EventPriority.HIGHEST, ironTargetHandler.priority());
        assertFalse(ironTargetHandler.ignoreCancelled());
        assertEquals(EventPriority.HIGHEST, ironDamageHandler.priority());
        assertFalse(ironDamageHandler.ignoreCancelled());
        assertEquals(EventPriority.HIGHEST, snowTargetHandler.priority());
        assertFalse(snowTargetHandler.ignoreCancelled());
        assertEquals(EventPriority.HIGHEST, snowDamageHandler.priority());
        assertFalse(snowDamageHandler.ignoreCancelled());
    }

    @Test
    void vanillaSafetyHandlersRunLastAndObserveAlreadyCancelledEvents() throws NoSuchMethodException {
        EventHandler transformHandler = RoyalGuardManager.class
                .getMethod("onGuardTransform", EntityTransformEvent.class)
                .getAnnotation(EventHandler.class);
        EventHandler blockHandler = RoyalGuardManager.class
                .getMethod("onGuardChangesBlock", EntityChangeBlockEvent.class)
                .getAnnotation(EventHandler.class);
        EventHandler interactionHandler = RoyalGuardManager.class
                .getMethod("onGuardInteractsWithFragileBlock", EntityInteractEvent.class)
                .getAnnotation(EventHandler.class);
        EventHandler sleepHandler = RoyalGuardManager.class
                .getMethod("onPlayerBedEnter", PlayerBedEnterEvent.class)
                .getAnnotation(EventHandler.class);

        assertEquals(EventPriority.HIGHEST, transformHandler.priority());
        assertFalse(transformHandler.ignoreCancelled());
        assertEquals(EventPriority.HIGHEST, blockHandler.priority());
        assertFalse(blockHandler.ignoreCancelled());
        assertEquals(EventPriority.HIGHEST, interactionHandler.priority());
        assertFalse(interactionHandler.ignoreCancelled());
        assertEquals(EventPriority.HIGHEST, sleepHandler.priority());
        assertFalse(sleepHandler.ignoreCancelled());
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
    void explicitSubcommandsAreIdempotentAndATypoNeverDismissesTheSquad() {
        GuardFixture fixture = new GuardFixture();

        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        manager.onCommand(fixture.owner, null, "garde", new String[]{"invoquer"});
        manager.onCommand(fixture.owner, null, "garde", new String[]{"commande-inconnue"});

        assertTrue(manager.hasActiveSquad(fixture.ownerId));
        assertEquals(2, manager.activeGuardCount(fixture.ownerId));
        assertEquals(2, guardFactory.requests.size());
        verify(fixture.owner).sendMessage(ChatColor.RED + "Sous-commande inconnue.");

        manager.onCommand(fixture.owner, null, "garde", new String[]{"renvoyer"});

        assertFalse(manager.hasActiveSquad(fixture.ownerId));
        verify(fixture.first.guard).remove();
        verify(fixture.second.guard).remove();
    }

    @Test
    void tabCompletionExposesTheSafeExplicitActions() {
        GuardFixture fixture = new GuardFixture();

        assertEquals(List.of("statut"),
                manager.onTabComplete(fixture.owner, null, "garde", new String[]{"st"}));
        assertEquals(List.of("invoquer", "renvoyer", "statut", "aide"),
                manager.onTabComplete(fixture.owner, null, "garde", new String[]{""}));
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
    void everyValidOwnerHitIncludingAProjectileCommandsBothGuards() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);

        LivingEntity target = livingEntity(fixture.world);
        when(target.getLocation()).thenReturn(new Location(fixture.world, 40.0D, 64.0D, 0.0D));
        Projectile projectile = mock(Projectile.class);
        DamageSource damageSource = mock(DamageSource.class);
        when(damageSource.getCausingEntity()).thenReturn(fixture.owner);

        EntityDamageByEntityEvent ownerAttack = mock(EntityDamageByEntityEvent.class);
        when(ownerAttack.getEntity()).thenReturn(target);
        when(ownerAttack.getDamager()).thenReturn(projectile);
        when(ownerAttack.getDamageSource()).thenReturn(damageSource);

        manager.onOwnerAttacks(ownerAttack);

        verify(fixture.first.guard).setTarget(target);
        verify(fixture.second.guard).setTarget(target);
    }

    @Test
    void hittingAnEnderDragonPartCommandsTheLivingParent() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);

        ComplexLivingEntity dragon = mock(ComplexLivingEntity.class);
        when(dragon.getUniqueId()).thenReturn(UUID.randomUUID());
        when(dragon.getWorld()).thenReturn(fixture.world);
        when(dragon.getLocation()).thenReturn(new Location(fixture.world, 20.0D, 70.0D, 0.0D));
        when(dragon.isValid()).thenReturn(true);
        when(dragon.isDead()).thenReturn(false);

        ComplexEntityPart dragonPart = mock(ComplexEntityPart.class);
        when(dragonPart.getParent()).thenReturn(dragon);

        EntityDamageByEntityEvent ownerAttack = mock(EntityDamageByEntityEvent.class);
        when(ownerAttack.getEntity()).thenReturn(dragonPart);
        when(ownerAttack.getDamager()).thenReturn(fixture.owner);

        manager.onOwnerAttacks(ownerAttack);

        verify(fixture.first.guard).setTarget(dragon);
        verify(fixture.second.guard).setTarget(dragon);

        EntityDamageByEntityEvent guardHitOnPart = mock(EntityDamageByEntityEvent.class);
        when(guardHitOnPart.getDamager()).thenReturn(fixture.first.guard);
        when(guardHitOnPart.getEntity()).thenReturn(dragonPart);
        manager.onGuardDamages(guardHitOnPart);

        verify(guardHitOnPart, never()).setCancelled(true);
    }

    @Test
    void aMobTargetingTheOwnerIsInterceptedBeforeItsFirstDamage() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        Mob threat = mob(fixture.world);
        // L'événement est gratuit : il peut couvrir toute la laisse, même au-delà du scan périodique.
        when(threat.getLocation()).thenReturn(new Location(fixture.world, 40.0D, 64.0D, 0.0D));
        when(threat.getTarget()).thenReturn(fixture.owner);

        EntityTargetLivingEntityEvent targetEvent = mock(EntityTargetLivingEntityEvent.class);
        when(targetEvent.getEntity()).thenReturn(threat);
        when(targetEvent.getTarget()).thenReturn(fixture.owner);
        when(targetEvent.getReason()).thenReturn(EntityTargetEvent.TargetReason.CLOSEST_PLAYER);

        manager.onEntityTargetsOwner(targetEvent);

        verify(fixture.first.guard).setTarget(threat);
        verify(fixture.second.guard).setTarget(threat);
    }

    @Test
    void aMobTargetingOneGuardIsInterceptedBeforeItsFirstDamage() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        Mob threat = mob(fixture.world);

        EntityTargetLivingEntityEvent targetEvent = mock(EntityTargetLivingEntityEvent.class);
        when(targetEvent.getEntity()).thenReturn(threat);
        when(targetEvent.getTarget()).thenReturn(fixture.first.guard);
        when(targetEvent.getReason()).thenReturn(EntityTargetEvent.TargetReason.CLOSEST_ENTITY);

        manager.onEntityTargetsOwner(targetEvent);

        verify(fixture.first.guard).setTarget(threat);
        verify(fixture.second.guard).setTarget(threat);
    }

    @Test
    void aRaiderFollowingItsLeaderStillCountsAsAHostileTarget() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        Mob raider = mob(fixture.world);

        EntityTargetLivingEntityEvent targetEvent = mock(EntityTargetLivingEntityEvent.class);
        when(targetEvent.getEntity()).thenReturn(raider);
        when(targetEvent.getTarget()).thenReturn(fixture.owner);
        when(targetEvent.getReason()).thenReturn(EntityTargetEvent.TargetReason.FOLLOW_LEADER);

        manager.onEntityTargetsOwner(targetEvent);

        verify(fixture.first.guard).setTarget(raider);
        verify(fixture.second.guard).setTarget(raider);
    }

    @Test
    void aNonHostileTemptTargetReasonNeverStartsACombat() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        Mob attractedAnimal = mob(fixture.world);

        EntityTargetLivingEntityEvent targetEvent = mock(EntityTargetLivingEntityEvent.class);
        when(targetEvent.getEntity()).thenReturn(attractedAnimal);
        when(targetEvent.getTarget()).thenReturn(fixture.owner);
        when(targetEvent.getReason()).thenReturn(EntityTargetEvent.TargetReason.TEMPT);

        manager.onEntityTargetsOwner(targetEvent);

        verify(fixture.first.guard, never()).setTarget(any(LivingEntity.class));
        verify(fixture.second.guard, never()).setTarget(any(LivingEntity.class));
    }

    @Test
    void fallbackAwarenessScanDetectsAMobTargetInjectedWithoutTargetEvent() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        Mob threat = mob(fixture.world);
        when(threat.getTarget()).thenReturn(fixture.owner);
        when(fixture.owner.getNearbyEntities(32.0D, 32.0D, 32.0D))
                .thenReturn(List.of(threat));

        manager.followActiveSquads();

        verify(fixture.first.guard).setTarget(threat);
        verify(fixture.second.guard).setTarget(threat);
    }

    @Test
    void oneMalformedNearbyEntityCannotHideAValidProactiveThreat() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);

        Entity malformed = mock(Entity.class);
        when(malformed.getUniqueId()).thenReturn(UUID.randomUUID());
        when(malformed.getWorld()).thenReturn(fixture.world);
        when(malformed.getLocation()).thenThrow(new IllegalStateException("Entité déchargée"));

        Mob validThreat = mob(fixture.world);
        when(validThreat.getTarget()).thenReturn(fixture.owner);
        when(fixture.owner.getNearbyEntities(32.0D, 32.0D, 32.0D))
                .thenReturn(List.of(malformed, validThreat));

        manager.followActiveSquads();

        verify(fixture.first.guard).setTarget(validThreat);
        verify(fixture.second.guard).setTarget(validThreat);
    }

    @Test
    void immediateAggressorPreemptsButDoesNotEraseTheOwnersQueuedTarget() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        LivingEntity commandedTarget = livingEntity(fixture.world);
        LivingEntity immediateAggressor = livingEntity(fixture.world);

        EntityDamageByEntityEvent ownerAttack = mock(EntityDamageByEntityEvent.class);
        when(ownerAttack.getEntity()).thenReturn(commandedTarget);
        when(ownerAttack.getDamager()).thenReturn(fixture.owner);
        manager.onOwnerAttacks(ownerAttack);

        EntityDamageByEntityEvent ownerDamaged = mock(EntityDamageByEntityEvent.class);
        when(ownerDamaged.getEntity()).thenReturn(fixture.owner);
        when(ownerDamaged.getDamager()).thenReturn(immediateAggressor);
        manager.onOwnerDamaged(ownerDamaged);
        verify(fixture.first.guard).setTarget(immediateAggressor);
        verify(fixture.second.guard).setTarget(immediateAggressor);

        when(immediateAggressor.isDead()).thenReturn(true);
        clearInvocations(fixture.first.guard, fixture.second.guard);

        manager.maintainActiveThreats();

        verify(fixture.first.guard).setTarget(commandedTarget);
        verify(fixture.second.guard).setTarget(commandedTarget);
    }

    @Test
    void proactiveDangerPreemptsButDoesNotEraseTheOwnersOffensiveOrder() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        LivingEntity commandedTarget = livingEntity(fixture.world);
        Mob proactiveThreat = mob(fixture.world);

        EntityDamageByEntityEvent ownerAttack = mock(EntityDamageByEntityEvent.class);
        when(ownerAttack.getEntity()).thenReturn(commandedTarget);
        when(ownerAttack.getDamager()).thenReturn(fixture.owner);
        manager.onOwnerAttacks(ownerAttack);

        EntityTargetLivingEntityEvent targetEvent = mock(EntityTargetLivingEntityEvent.class);
        when(targetEvent.getEntity()).thenReturn(proactiveThreat);
        when(targetEvent.getTarget()).thenReturn(fixture.owner);
        when(targetEvent.getReason()).thenReturn(EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
        manager.onEntityTargetsOwner(targetEvent);

        verify(fixture.first.guard).setTarget(proactiveThreat);
        verify(fixture.second.guard).setTarget(proactiveThreat);

        when(proactiveThreat.isDead()).thenReturn(true);
        clearInvocations(fixture.first.guard, fixture.second.guard);

        manager.maintainActiveThreats();

        verify(fixture.first.guard).setTarget(commandedTarget);
        verify(fixture.second.guard).setTarget(commandedTarget);
    }

    @Test
    void successiveOwnerTargetsAreQueuedAndFinishedInsteadOfBeingForgotten() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        LivingEntity firstTarget = livingEntity(fixture.world);
        LivingEntity secondTarget = livingEntity(fixture.world);

        EntityDamageByEntityEvent firstAttack = mock(EntityDamageByEntityEvent.class);
        when(firstAttack.getEntity()).thenReturn(firstTarget);
        when(firstAttack.getDamager()).thenReturn(fixture.owner);
        manager.onOwnerAttacks(firstAttack);

        EntityDamageByEntityEvent secondAttack = mock(EntityDamageByEntityEvent.class);
        when(secondAttack.getEntity()).thenReturn(secondTarget);
        when(secondAttack.getDamager()).thenReturn(fixture.owner);
        manager.onOwnerAttacks(secondAttack);

        verify(fixture.first.guard).setTarget(secondTarget);
        verify(fixture.second.guard).setTarget(secondTarget);

        when(secondTarget.isDead()).thenReturn(true);
        clearInvocations(fixture.first.guard, fixture.second.guard);

        manager.maintainActiveThreats();

        verify(fixture.first.guard).setTarget(firstTarget);
        verify(fixture.second.guard).setTarget(firstTarget);
    }

    @Test
    void ownerTamedAnimalsAreNeverAddedAsGuardTargets() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);

        Wolf pet = mock(Wolf.class);
        when(pet.getUniqueId()).thenReturn(UUID.randomUUID());
        when(pet.getWorld()).thenReturn(fixture.world);
        when(pet.getLocation()).thenReturn(new Location(fixture.world, 1.0D, 64.0D, 0.0D));
        when(pet.isValid()).thenReturn(true);
        when(pet.isDead()).thenReturn(false);
        when(pet.getOwner()).thenReturn(fixture.owner);

        EntityDamageByEntityEvent accidentalHit = mock(EntityDamageByEntityEvent.class);
        when(accidentalHit.getEntity()).thenReturn(pet);
        when(accidentalHit.getDamager()).thenReturn(fixture.owner);

        manager.onOwnerAttacks(accidentalHit);

        verify(fixture.first.guard, never()).setTarget(pet);
        verify(fixture.second.guard, never()).setTarget(pet);
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

        // Une menace de même urgence reste stable tant qu'elle est vivante ; une fois éliminée,
        // le prochain véritable agresseur devient immédiatement la cible active.
        when(attacker.isDead()).thenReturn(true);

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
        verify(ownerTarget).setTarget(projectileAttacker);
        verify(ownerTarget, never()).setCancelled(true);

        EntityTargetLivingEntityEvent siblingTarget = mock(EntityTargetLivingEntityEvent.class);
        when(siblingTarget.getEntity()).thenReturn(fixture.first.guard);
        when(siblingTarget.getTarget()).thenReturn(fixture.second.guard);
        manager.onGuardTargets(siblingTarget);
        verify(siblingTarget).setTarget(projectileAttacker);
        verify(siblingTarget, never()).setCancelled(true);

        EntityTargetLivingEntityEvent unrelatedTarget = mock(EntityTargetLivingEntityEvent.class);
        LivingEntity unrelatedTargetEntity = livingEntity(fixture.world);
        when(unrelatedTarget.getEntity()).thenReturn(fixture.first.guard);
        when(unrelatedTarget.getTarget()).thenReturn(unrelatedTargetEntity);
        manager.onGuardTargets(unrelatedTarget);
        verify(unrelatedTarget).setTarget(projectileAttacker);
        verify(unrelatedTarget, never()).setCancelled(true);

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
    void anAttackOnEitherGuardMakesTheWholeDuoDefendItself() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        LivingEntity attacker = livingEntity(fixture.world);

        EntityDamageByEntityEvent attack = mock(EntityDamageByEntityEvent.class);
        when(attack.isCancelled()).thenReturn(false);
        when(attack.getEntity()).thenReturn(fixture.first.guard);
        when(attack.getDamager()).thenReturn(attacker);

        manager.onGuardDamaged(attack);

        verify(fixture.first.guard).setTarget(attacker);
        verify(fixture.second.guard).setTarget(attacker);
    }

    @Test
    void anUnrelatedVanillaTargetCannotEraseTheCurrentAggressor() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        LivingEntity attacker = livingEntity(fixture.world);

        EntityDamageByEntityEvent attack = mock(EntityDamageByEntityEvent.class);
        when(attack.isCancelled()).thenReturn(false);
        when(attack.getEntity()).thenReturn(fixture.first.guard);
        when(attack.getDamager()).thenReturn(attacker);
        manager.onGuardDamaged(attack);

        clearInvocations(fixture.first.guard);
        EntityTargetLivingEntityEvent distraction = mock(EntityTargetLivingEntityEvent.class);
        when(distraction.getEntity()).thenReturn(fixture.first.guard);
        LivingEntity unrelatedTarget = livingEntity(fixture.world);
        when(distraction.getTarget()).thenReturn(unrelatedTarget);

        manager.onGuardTargets(distraction);

        verify(distraction).setTarget(attacker);
        verify(distraction, never()).setCancelled(true);
        // Le listener corrige l'événement lui-même et n'appelle pas setTarget(), ce qui évite
        // une récursion de EntityTargetLivingEntityEvent.
        verify(fixture.first.guard, never()).setTarget(any(LivingEntity.class));
        verify(fixture.first.guard, never()).setTarget((LivingEntity) null);
    }

    @Test
    void anInvalidSwingCannotEraseTheCurrentAggressor() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        LivingEntity attacker = livingEntity(fixture.world);

        EntityDamageByEntityEvent attack = mock(EntityDamageByEntityEvent.class);
        when(attack.isCancelled()).thenReturn(false);
        when(attack.getEntity()).thenReturn(fixture.first.guard);
        when(attack.getDamager()).thenReturn(attacker);
        manager.onGuardDamaged(attack);

        clearInvocations(fixture.first.guard);
        EntityDamageByEntityEvent invalidSwing = mock(EntityDamageByEntityEvent.class);
        when(invalidSwing.isCancelled()).thenReturn(false);
        when(invalidSwing.getDamager()).thenReturn(fixture.first.guard);
        LivingEntity unrelatedTarget = livingEntity(fixture.world);
        when(invalidSwing.getEntity()).thenReturn(unrelatedTarget);

        manager.onGuardDamages(invalidSwing);

        verify(invalidSwing).setCancelled(true);
        verify(fixture.first.guard).setTarget(attacker);
        verify(fixture.first.guard, never()).setTarget((LivingEntity) null);
    }

    @Test
    void targetFailureOnOneGuardDoesNotPreventItsPartnerFromDefending() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        LivingEntity attacker = livingEntity(fixture.world);
        doThrow(new IllegalStateException("Cible temporairement indisponible"))
                .when(fixture.first.guard).setTarget(attacker);

        EntityDamageByEntityEvent attack = mock(EntityDamageByEntityEvent.class);
        when(attack.isCancelled()).thenReturn(false);
        when(attack.getEntity()).thenReturn(fixture.first.guard);
        when(attack.getDamager()).thenReturn(attacker);

        manager.onGuardDamaged(attack);

        verify(fixture.second.guard).setTarget(attacker);
    }

    @Test
    void ironGolemsCannotKeepOrDamageARoyalGuardAndACancelledHitTriggersDefence() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        IronGolem golem = ironGolem(fixture.world);
        when(golem.getTarget()).thenReturn(fixture.first.guard);

        EntityTargetLivingEntityEvent targetEvent = mock(EntityTargetLivingEntityEvent.class);
        when(targetEvent.getEntity()).thenReturn(golem);
        when(targetEvent.getTarget()).thenReturn(fixture.first.guard);

        manager.onIronGolemTargetsGuard(targetEvent);

        verify(targetEvent).setTarget(null);
        verify(targetEvent).setCancelled(true);
        verify(golem).setTarget((LivingEntity) null);

        clearInvocations(fixture.first.guard, fixture.second.guard);
        EntityDamageByEntityEvent damageEvent = mock(EntityDamageByEntityEvent.class);
        when(damageEvent.getEntity()).thenReturn(fixture.first.guard);
        when(damageEvent.getDamager()).thenReturn(golem);

        manager.onIronGolemDamagesGuard(damageEvent);

        verify(damageEvent).setCancelled(true);
        verify(fixture.first.guard).setTarget(golem);
        verify(fixture.second.guard).setTarget(golem);
    }

    @Test
    void golemTargetAcquiredAfterTheEventIsPurgedOnTheNextTick() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        IronGolem golem = ironGolem(fixture.world);
        // Pendant l'événement, getTarget() expose encore l'ancienne valeur ; la nouvelle cible
        // n'apparaît qu'au tick suivant, ce qui reproduit la fenêtre observée en jeu.
        when(golem.getTarget()).thenReturn(null, fixture.first.guard);

        EntityTargetLivingEntityEvent targetEvent = mock(EntityTargetLivingEntityEvent.class);
        when(targetEvent.getEntity()).thenReturn(golem);
        when(targetEvent.getTarget()).thenReturn(fixture.first.guard);

        manager.onIronGolemTargetsGuard(targetEvent);

        verify(golem, never()).setTarget((LivingEntity) null);
        server.getScheduler().performTicks(1);

        verify(golem).setTarget((LivingEntity) null);
        verify(golem).setAggressive(false);
    }

    @Test
    void pendingGolemDeaggroVerificationIsCancelledOnManagerShutdown() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        IronGolem golem = ironGolem(fixture.world);
        when(golem.getTarget()).thenReturn(null, fixture.first.guard);

        EntityTargetLivingEntityEvent targetEvent = mock(EntityTargetLivingEntityEvent.class);
        when(targetEvent.getEntity()).thenReturn(golem);
        when(targetEvent.getTarget()).thenReturn(fixture.first.guard);
        manager.onIronGolemTargetsGuard(targetEvent);

        manager.shutdown();
        server.getScheduler().performTicks(1);

        verify(golem, never()).setTarget((LivingEntity) null);
    }

    @Test
    void boundedFallbackSweepPurgesATargetInjectedWithoutABukkitTargetEvent() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        IronGolem golem = ironGolem(fixture.world);
        when(golem.getTarget()).thenReturn(fixture.first.guard);
        when(fixture.owner.getNearbyEntities(32.0D, 32.0D, 32.0D))
                .thenReturn(List.<Entity>of(golem));

        manager.followActiveSquads();

        verify(golem).setTarget((LivingEntity) null);
        verify(golem).setAggressive(false);
        verify(fixture.first.guard, never()).setTarget(golem);
        verify(fixture.second.guard, never()).setTarget(golem);
    }

    @Test
    void golemRetaliationCanBeDisabledWithoutAllowingDamage() {
        GuardFixture fixture = new GuardFixture();
        plugin.getConfig().set("garde.iron-golem-retaliation", false);
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        IronGolem golem = ironGolem(fixture.world);
        when(golem.getTarget()).thenReturn(fixture.first.guard);

        EntityDamageByEntityEvent damageEvent = mock(EntityDamageByEntityEvent.class);
        when(damageEvent.getEntity()).thenReturn(fixture.first.guard);
        when(damageEvent.getDamager()).thenReturn(golem);

        manager.onIronGolemDamagesGuard(damageEvent);

        verify(damageEvent).setCancelled(true);
        verify(fixture.first.guard, never()).setTarget(golem);
        verify(fixture.second.guard, never()).setTarget(golem);
    }

    @Test
    void ironGolemNeutralityStillAllowsTheDuoToDefendItsOwner() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        IronGolem golem = ironGolem(fixture.world);

        EntityDamageByEntityEvent ownerAttack = mock(EntityDamageByEntityEvent.class);
        when(ownerAttack.isCancelled()).thenReturn(false);
        when(ownerAttack.getEntity()).thenReturn(fixture.owner);
        when(ownerAttack.getDamager()).thenReturn(golem);
        manager.onOwnerDamaged(ownerAttack);

        verify(fixture.first.guard).setTarget(golem);
        verify(fixture.second.guard).setTarget(golem);

        clearInvocations(fixture.first.guard, fixture.second.guard);
        when(golem.getTarget()).thenReturn(fixture.first.guard);
        EntityTargetLivingEntityEvent retaliation = mock(EntityTargetLivingEntityEvent.class);
        when(retaliation.getEntity()).thenReturn(golem);
        when(retaliation.getTarget()).thenReturn(fixture.first.guard);
        manager.onIronGolemTargetsGuard(retaliation);

        verify(retaliation).setTarget(null);
        verify(retaliation).setCancelled(true);
        verify(fixture.first.guard, never()).setTarget((LivingEntity) null);
        verify(fixture.second.guard, never()).setTarget((LivingEntity) null);

        // La neutralité du golem est unidirectionnelle : elle n'efface jamais la menace
        // mémorisée par les gardes lorsqu'ils protègent leur propriétaire.
        manager.maintainActiveThreats();
        verify(fixture.first.guard).setTarget(golem);
        verify(fixture.second.guard).setTarget(golem);
    }

    @Test
    void disablingIronGolemNeutralityMakesTheDuoRetaliateNormally() {
        GuardFixture fixture = new GuardFixture();
        plugin.getConfig().set("garde.iron-golem-neutrality", false);
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        IronGolem golem = ironGolem(fixture.world);

        EntityTargetLivingEntityEvent targetEvent = mock(EntityTargetLivingEntityEvent.class);
        when(targetEvent.getEntity()).thenReturn(golem);
        when(targetEvent.getTarget()).thenReturn(fixture.first.guard);
        manager.onIronGolemTargetsGuard(targetEvent);
        verify(targetEvent, never()).setCancelled(true);

        EntityDamageByEntityEvent damageEvent = mock(EntityDamageByEntityEvent.class);
        when(damageEvent.isCancelled()).thenReturn(false);
        when(damageEvent.getEntity()).thenReturn(fixture.first.guard);
        when(damageEvent.getDamager()).thenReturn(golem);
        manager.onIronGolemDamagesGuard(damageEvent);
        verify(damageEvent, never()).setCancelled(true);

        manager.onGuardDamaged(damageEvent);

        verify(fixture.first.guard).setTarget(golem);
        verify(fixture.second.guard).setTarget(golem);
    }

    @Test
    void retaliationTargetIsReassertedByTheSingleGlobalCombatTask() {
        manager.shutdown();
        manager = new RoyalGuardManager(plugin, (guard, target, speed) -> true, true, guardFactory,
                (owner, slot) -> owner.getLocation().clone().add(slot == 0 ? 2.0D : -2.0D, 0.0D, 0.0D));

        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        LivingEntity attacker = livingEntity(fixture.world);

        EntityDamageByEntityEvent attack = mock(EntityDamageByEntityEvent.class);
        when(attack.isCancelled()).thenReturn(false);
        when(attack.getEntity()).thenReturn(fixture.first.guard);
        when(attack.getDamager()).thenReturn(attacker);
        manager.onGuardDamaged(attack);

        clearInvocations(fixture.first.guard, fixture.second.guard);
        server.getScheduler().performTicks(1);

        verify(fixture.first.guard).setTarget(attacker);
        verify(fixture.second.guard).setTarget(attacker);

        clearInvocations(fixture.first.guard, fixture.second.guard);
        server.getScheduler().performTicks(5);

        verify(fixture.first.guard).setTarget(attacker);
        verify(fixture.second.guard).setTarget(attacker);
    }

    @Test
    void ownerFriendlyFireIsCancelledWithoutTurningTheGuardsAgainstTheirOwner() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);

        EntityDamageByEntityEvent friendlyFire = mock(EntityDamageByEntityEvent.class);
        when(friendlyFire.isCancelled()).thenReturn(false);
        when(friendlyFire.getEntity()).thenReturn(fixture.first.guard);
        when(friendlyFire.getDamager()).thenReturn(fixture.owner);

        manager.onGuardFriendlyFire(friendlyFire);

        verify(friendlyFire).setCancelled(true);
        verify(fixture.first.guard, never()).setTarget(fixture.owner);
        verify(fixture.second.guard, never()).setTarget(fixture.owner);
    }

    @Test
    void damageSourceCausingEntityWinsOverTheDirectProjectile() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        LivingEntity attacker = livingEntity(fixture.world);
        Projectile directProjectile = mock(Projectile.class);
        DamageSource damageSource = mock(DamageSource.class);
        when(damageSource.getCausingEntity()).thenReturn(attacker);

        EntityDamageByEntityEvent attack = mock(EntityDamageByEntityEvent.class);
        when(attack.isCancelled()).thenReturn(false);
        when(attack.getEntity()).thenReturn(fixture.owner);
        when(attack.getDamager()).thenReturn(directProjectile);
        when(attack.getDamageSource()).thenReturn(damageSource);

        manager.onOwnerDamaged(attack);

        verify(fixture.first.guard).setTarget(attacker);
        verify(fixture.second.guard).setTarget(attacker);
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
    void aTemporaryRespawnFailureIsAutomaticallyRetried() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        manager.onGuardDeath(deathOf(fixture.first.guard));

        guardFactory.clearAvailable();
        server.getScheduler().performTicks(400);
        assertEquals(3, guardFactory.requests.size());
        assertEquals(1, manager.activeGuardCount(fixture.ownerId));

        GuardDouble replacement = guard(fixture.world, fixture.ownerId, 0);
        guardFactory.add(replacement.guard);
        server.getScheduler().performTicks(400);

        assertEquals(4, guardFactory.requests.size());
        assertEquals(2, manager.activeGuardCount(fixture.ownerId));
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
    void guardsNavigateToDistinctRearFormationAnchorsInsteadOfTheOwnerPosition() {
        manager.shutdown();
        NavigationRecorder navigator = new NavigationRecorder();
        manager = new RoyalGuardManager(plugin, navigator, false, guardFactory,
                (owner, slot) -> owner.getLocation().clone().add(slot == 0 ? 2.0D : -2.0D, 0.0D, 0.0D));

        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);

        manager.followActiveSquads();

        Location firstTarget = navigator.lastTargetFor(fixture.first.guard);
        Location secondTarget = navigator.lastTargetFor(fixture.second.guard);
        assertNotNull(firstTarget);
        assertNotNull(secondTarget);
        assertTrue(firstTarget.getZ() < fixture.owner.getLocation().getZ());
        assertTrue(secondTarget.getZ() < fixture.owner.getLocation().getZ());
        assertTrue(firstTarget.getX() * secondTarget.getX() < 0.0D);
        assertTrue(firstTarget.distanceSquared(fixture.owner.getLocation()) > 4.0D);
        assertTrue(secondTarget.distanceSquared(fixture.owner.getLocation()) > 4.0D);
    }

    @Test
    void unsafeFormationConfigurationIsAutomaticallyKeptOutsidePersonalSpace() {
        GuardFixture fixture = new GuardFixture();
        plugin.getConfig().set("garde.formation-side-distance", 0.1D);
        plugin.getConfig().set("garde.formation-rear-distance", 0.1D);
        plugin.getConfig().set("garde.personal-space-radius", 4.0D);

        Location firstAnchor = manager.getFormationTarget(fixture.owner, 0);
        Location secondAnchor = manager.getFormationTarget(fixture.owner, 1);
        Location ownerLocation = fixture.owner.getLocation();

        double firstHorizontalDistance = Math.hypot(
                firstAnchor.getX() - ownerLocation.getX(),
                firstAnchor.getZ() - ownerLocation.getZ());
        double secondHorizontalDistance = Math.hypot(
                secondAnchor.getX() - ownerLocation.getX(),
                secondAnchor.getZ() - ownerLocation.getZ());

        assertTrue(firstHorizontalDistance >= 4.75D - 1.0E-9D);
        assertTrue(secondHorizontalDistance >= 4.75D - 1.0E-9D);
        assertTrue(Math.abs(firstAnchor.getX() - secondAnchor.getX()) >= 1.8D);
        assertTrue(firstAnchor.getZ() < ownerLocation.getZ());
        assertTrue(secondAnchor.getZ() < ownerLocation.getZ());
    }

    @Test
    void followRadiusIsAutomaticallyLargeEnoughForTheConfiguredFormation() {
        GuardFixture fixture = new GuardFixture();
        plugin.getConfig().set("garde.follow-radius", 2.0D);
        plugin.getConfig().set("garde.formation-side-distance", 8.0D);
        plugin.getConfig().set("garde.formation-rear-distance", 8.0D);
        manager.onCommand(fixture.owner, null, "garde", new String[0]);

        when(fixture.first.guard.getLocation()).thenReturn(manager.getFormationTarget(fixture.owner, 0));
        when(fixture.second.guard.getLocation()).thenReturn(manager.getFormationTarget(fixture.owner, 1));
        clearInvocations(fixture.first.guard, fixture.second.guard);

        manager.followActiveSquads();

        verify(fixture.first.guard, never()).teleport(any(Location.class));
        verify(fixture.second.guard, never()).teleport(any(Location.class));
    }

    @Test
    void aGuardAlreadyInFormationStopsItsObsoletePath() {
        GuardFixture fixture = new GuardFixture();
        NavigationRecorder recorder = new NavigationRecorder();
        manager.shutdown();
        manager = new RoyalGuardManager(plugin, recorder, false, guardFactory,
                (owner, slot) -> owner.getLocation().clone().add(slot == 0 ? 2.0D : -2.0D, 0.0D, 0.0D));
        manager.onCommand(fixture.owner, null, "garde", new String[0]);

        when(fixture.first.guard.getLocation()).thenReturn(manager.getFormationTarget(fixture.owner, 0));
        when(fixture.second.guard.getLocation()).thenReturn(manager.getFormationTarget(fixture.owner, 1));

        manager.followActiveSquads();

        assertEquals(2, recorder.stopCalls());
        assertTrue(recorder.calls.isEmpty());
    }

    @Test
    void aGuardInFrontOfTheCameraFirstReceivesALateralClearanceWaypoint() {
        manager.shutdown();
        NavigationRecorder navigator = new NavigationRecorder();
        manager = new RoyalGuardManager(plugin, navigator, false, guardFactory,
                (owner, slot) -> owner.getLocation().clone().add(slot == 0 ? 2.0D : -2.0D, 0.0D, 0.0D));

        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        when(fixture.first.guard.getLocation()).thenReturn(
                new Location(fixture.world, 0.0D, 64.0D, 2.0D));

        manager.followActiveSquads();

        Location clearanceTarget = navigator.lastTargetFor(fixture.first.guard);
        assertNotNull(clearanceTarget);
        assertTrue(Math.abs(clearanceTarget.getX()) >= 3.0D);
        assertTrue(clearanceTarget.getZ() >= 0.0D);
        assertTrue(clearanceTarget.getZ() <= 1.25D);
    }

    @Test
    void aGuardOverlappingTheOwnerIsImmediatelyRecalledToASafeSide() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        when(fixture.first.guard.getLocation()).thenReturn(fixture.owner.getLocation().clone());

        manager.followActiveSquads();

        verify(fixture.first.guard).teleport(any(Location.class));
    }

    @Test
    void combatUsesTheWiderLeashAndDoesNotRecallAGuardBeforeItFinishesItsTarget() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        LivingEntity target = livingEntity(fixture.world);
        when(target.getLocation()).thenReturn(new Location(fixture.world, 47.0D, 64.0D, 0.0D));

        EntityDamageByEntityEvent ownerAttack = mock(EntityDamageByEntityEvent.class);
        when(ownerAttack.getEntity()).thenReturn(target);
        when(ownerAttack.getDamager()).thenReturn(fixture.owner);
        manager.onOwnerAttacks(ownerAttack);

        // Le garde a légèrement dépassé la cible pour la contourner : la marge de combat
        // doit éviter un rappel/poursuite en boucle au bord exact des 48 blocs.
        when(fixture.first.guard.getLocation()).thenReturn(
                new Location(fixture.world, 51.0D, 64.0D, 0.0D));
        clearInvocations(fixture.first.guard);

        manager.followActiveSquads();

        verify(fixture.first.guard, never()).teleport(any(Location.class));
        verify(fixture.first.guard).setTarget(target);
    }

    @Test
    void aNavigationFailureOnOneGuardDoesNotStopTheOtherGuard() {
        manager.shutdown();
        AtomicInteger navigationAttempts = new AtomicInteger();
        manager = new RoyalGuardManager(plugin, (guard, target, speed) -> {
            if (navigationAttempts.getAndIncrement() == 0) {
                throw new IllegalStateException("Pathfinder temporairement indisponible");
            }
            return true;
        }, false, guardFactory,
                (owner, slot) -> owner.getLocation().clone().add(slot == 0 ? 2.0D : -2.0D, 0.0D, 0.0D));

        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        when(fixture.first.guard.getLocation()).thenReturn(
                new Location(fixture.world, 10.0D, 64.0D, 0.0D));
        when(fixture.second.guard.getLocation()).thenReturn(
                new Location(fixture.world, -10.0D, 64.0D, 0.0D));

        manager.followActiveSquads();

        assertEquals(2, navigationAttempts.get());
        verify(fixture.first.guard).teleport(any(Location.class));
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
        verify(fixture.first.guard).setAggressive(false);
        verify(fixture.second.guard).setAggressive(false);
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
    void aRejectedTeleportIsNotReportedAsASuccessfulRecall() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        when(fixture.first.guard.getLocation()).thenReturn(
                new Location(fixture.world, 30.0D, 64.0D, 0.0D));
        when(fixture.first.guard.teleport(any(Location.class))).thenReturn(false);

        manager.followActiveSquads();

        verify(fixture.first.guard).teleport(any(Location.class));
        verify(fixture.first.guard, never()).setFallDistance(0.0F);
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
        when(fixture.first.guard.getLocation()).thenReturn(new Location(fixture.world, 60.0D, 64.0D, 0.0D));

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
    void malformedRoyalMarkerIsRemovedWhenItsChunkLoads() {
        Husk malformed = mock(Husk.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(malformed.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(Keys.royalGuardType(), PersistentDataType.STRING))
                .thenReturn(RoyalGuardManager.GUARD_TYPE);
        when(malformed.isDead()).thenReturn(false);

        EntitiesLoadEvent event = mock(EntitiesLoadEvent.class);
        when(event.getEntities()).thenReturn(List.<Entity>of(malformed));

        manager.onEntitiesLoad(event);

        verify(malformed).remove();
    }

    @Test
    void malformedRoyalMarkerCannotDropRoyalEquipment() {
        Husk malformed = mock(Husk.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(malformed.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(Keys.royalGuardType(), PersistentDataType.STRING))
                .thenReturn(RoyalGuardManager.GUARD_TYPE);

        List<ItemStack> drops = new ArrayList<>();
        drops.add(mock(ItemStack.class));
        EntityDeathEvent death = deathOf(malformed, drops);

        manager.onGuardDeath(death);

        assertTrue(drops.isEmpty());
        verify(death).setDroppedExp(0);
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


    @Test
    void peacefulWorldRefusesSummonWithoutCreatingAPartialSquad() {
        GuardFixture fixture = new GuardFixture();
        when(fixture.world.getDifficulty()).thenReturn(Difficulty.PEACEFUL);

        manager.onCommand(fixture.owner, null, "garde", new String[]{"invoquer"});

        assertFalse(manager.hasActiveSquad(fixture.ownerId));
        assertEquals(0, guardFactory.requests.size());
        verify(fixture.owner).sendMessage(ChatColor.RED
                + "Les gardes royaux ne peuvent pas rester dans un monde en difficulté Paisible.");
    }

    @Test
    void activeSquadIsDismissedWhenItsWorldBecomesPeaceful() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        when(fixture.world.getDifficulty()).thenReturn(Difficulty.PEACEFUL);

        manager.followActiveSquads();

        assertFalse(manager.hasActiveSquad(fixture.ownerId));
        verify(fixture.first.guard).remove();
        verify(fixture.second.guard).remove();
        verify(fixture.owner).sendMessage(ChatColor.RED
                + "Tes gardes royaux ont été renvoyés : les Husks ne persistent pas en difficulté Paisible.");
    }

    @Test
    void maintenanceStopsConversionReinforcementsAndVehicleEntrapment() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);

        AttributeInstance reinforcementChance = mock(AttributeInstance.class);
        AttributeModifier modifier = mock(AttributeModifier.class);
        when(fixture.first.guard.getAttribute(Attribute.SPAWN_REINFORCEMENTS))
                .thenReturn(reinforcementChance);
        when(reinforcementChance.getBaseValue()).thenReturn(0.25D);
        when(reinforcementChance.getModifiers()).thenReturn(List.of(modifier));
        when(fixture.first.guard.isConverting()).thenReturn(true);
        when(fixture.first.guard.isInsideVehicle()).thenReturn(true);

        manager.followActiveSquads();

        verify(reinforcementChance).setBaseValue(0.0D);
        verify(reinforcementChance).removeModifier(modifier);
        verify(fixture.first.guard).setConversionTime(-1);
        verify(fixture.first.guard).stopDrowning();
        verify(fixture.first.guard).leaveVehicle();
    }

    @Test
    void guardTransformationIsCancelledAndItsConversionStateIsReset() {
        GuardFixture fixture = new GuardFixture();
        when(fixture.first.guard.isConverting()).thenReturn(true);
        EntityTransformEvent event = mock(EntityTransformEvent.class);
        when(event.getEntity()).thenReturn(fixture.first.guard);

        manager.onGuardTransform(event);

        verify(event).setCancelled(true);
        verify(fixture.first.guard).setConversionTime(-1);
        verify(fixture.first.guard).stopDrowning();
    }

    @Test
    void transformedMarkedEntityIsRemovedEvenWhenItIsNoLongerAHusk() {
        Zombie transformedGuard = mock(Zombie.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(transformedGuard.getUniqueId()).thenReturn(UUID.randomUUID());
        when(transformedGuard.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(Keys.royalGuardType(), PersistentDataType.STRING))
                .thenReturn(RoyalGuardManager.GUARD_TYPE);
        when(transformedGuard.isDead()).thenReturn(false);

        EntitiesLoadEvent event = mock(EntitiesLoadEvent.class);
        when(event.getEntities()).thenReturn(List.<Entity>of(transformedGuard));

        manager.onEntitiesLoad(event);

        verify(transformedGuard).remove();
    }

    @Test
    void guardsCannotChangeBlocksOrTrampleFragileBlocks() {
        GuardFixture fixture = new GuardFixture();

        EntityChangeBlockEvent blockChange = mock(EntityChangeBlockEvent.class);
        when(blockChange.getEntity()).thenReturn(fixture.first.guard);
        manager.onGuardChangesBlock(blockChange);
        verify(blockChange).setCancelled(true);

        Block turtleEgg = mock(Block.class);
        when(turtleEgg.getType()).thenReturn(Material.TURTLE_EGG);
        EntityInteractEvent turtleInteraction = mock(EntityInteractEvent.class);
        when(turtleInteraction.getEntity()).thenReturn(fixture.first.guard);
        when(turtleInteraction.getBlock()).thenReturn(turtleEgg);
        manager.onGuardInteractsWithFragileBlock(turtleInteraction);
        verify(turtleInteraction).setCancelled(true);

        Block farmland = mock(Block.class);
        when(farmland.getType()).thenReturn(Material.FARMLAND);
        EntityInteractEvent farmlandInteraction = mock(EntityInteractEvent.class);
        when(farmlandInteraction.getEntity()).thenReturn(fixture.first.guard);
        when(farmlandInteraction.getBlock()).thenReturn(farmland);
        manager.onGuardInteractsWithFragileBlock(farmlandInteraction);
        verify(farmlandInteraction).setCancelled(true);

        Block pressurePlate = mock(Block.class);
        when(pressurePlate.getType()).thenReturn(Material.STONE_PRESSURE_PLATE);
        EntityInteractEvent pressurePlateInteraction = mock(EntityInteractEvent.class);
        when(pressurePlateInteraction.getEntity()).thenReturn(fixture.first.guard);
        when(pressurePlateInteraction.getBlock()).thenReturn(pressurePlate);
        manager.onGuardInteractsWithFragileBlock(pressurePlateInteraction);
        verify(pressurePlateInteraction, never()).setCancelled(true);
    }

    @Test
    void blockProtectionCanBeDisabledExplicitly() {
        GuardFixture fixture = new GuardFixture();
        plugin.getConfig().set("garde.prevent-block-damage", false);
        EntityChangeBlockEvent blockChange = mock(EntityChangeBlockEvent.class);
        when(blockChange.getEntity()).thenReturn(fixture.first.guard);

        manager.onGuardChangesBlock(blockChange);

        verify(blockChange, never()).setCancelled(true);
    }

    @Test
    void harmlessRoyalGuardsAloneDoNotBlockTheirOwnersBed() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);

        Block bed = mock(Block.class);
        when(bed.getWorld()).thenReturn(fixture.world);
        when(bed.getLocation()).thenReturn(new Location(fixture.world, 0.0D, 64.0D, 0.0D));
        when(fixture.world.getNearbyEntities(
                any(Location.class), eq(8.5D), eq(5.5D), eq(8.5D)))
                .thenReturn(List.<Entity>of(fixture.first.guard, fixture.second.guard));

        PlayerBedEnterEvent event = mock(PlayerBedEnterEvent.class);
        when(event.getPlayer()).thenReturn(fixture.owner);
        when(event.getBed()).thenReturn(bed);
        when(event.getBedEnterResult()).thenReturn(PlayerBedEnterEvent.BedEnterResult.NOT_SAFE);
        when(event.useBed()).thenReturn(Event.Result.DEFAULT);

        manager.onPlayerBedEnter(event);

        verify(event).setUseBed(Event.Result.ALLOW);
    }

    @Test
    void aRealMonsterStillBlocksTheBedNearRoyalGuards() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);

        Block bed = mock(Block.class);
        when(bed.getWorld()).thenReturn(fixture.world);
        when(bed.getLocation()).thenReturn(new Location(fixture.world, 0.0D, 64.0D, 0.0D));
        Husk monster = unmarkedHusk(fixture.world);
        when(fixture.world.getNearbyEntities(
                any(Location.class), eq(8.5D), eq(5.5D), eq(8.5D)))
                .thenReturn(List.<Entity>of(fixture.first.guard, fixture.second.guard, monster));

        PlayerBedEnterEvent event = mock(PlayerBedEnterEvent.class);
        when(event.getPlayer()).thenReturn(fixture.owner);
        when(event.getBed()).thenReturn(bed);
        when(event.getBedEnterResult()).thenReturn(PlayerBedEnterEvent.BedEnterResult.NOT_SAFE);
        when(event.useBed()).thenReturn(Event.Result.DEFAULT);

        manager.onPlayerBedEnter(event);

        verify(event, never()).setUseBed(Event.Result.ALLOW);
    }

    @Test
    void anExplicitBedDenialFromAnotherPluginIsPreserved() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);

        PlayerBedEnterEvent event = mock(PlayerBedEnterEvent.class);
        when(event.getBedEnterResult()).thenReturn(PlayerBedEnterEvent.BedEnterResult.NOT_SAFE);
        when(event.useBed()).thenReturn(Event.Result.DENY);

        manager.onPlayerBedEnter(event);

        verify(event, never()).setUseBed(Event.Result.ALLOW);
    }

    @Test
    void snowGolemsCannotTargetOrDamageRoyalGuardsAndNeverTriggerRetaliation() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        Snowman golem = snowGolem(fixture.world);
        when(golem.getTarget()).thenReturn(fixture.first.guard);

        EntityTargetLivingEntityEvent targetEvent = mock(EntityTargetLivingEntityEvent.class);
        when(targetEvent.getEntity()).thenReturn(golem);
        when(targetEvent.getTarget()).thenReturn(fixture.first.guard);

        manager.onSnowGolemTargetsGuard(targetEvent);

        verify(targetEvent).setTarget(null);
        verify(targetEvent).setCancelled(true);
        verify(golem).setTarget((LivingEntity) null);

        clearInvocations(fixture.first.guard, fixture.second.guard);
        EntityDamageByEntityEvent damageEvent = mock(EntityDamageByEntityEvent.class);
        when(damageEvent.getEntity()).thenReturn(fixture.first.guard);
        when(damageEvent.getDamager()).thenReturn(golem);

        manager.onSnowGolemDamagesGuard(damageEvent);

        verify(damageEvent).setCancelled(true);
        verify(fixture.first.guard, never()).setTarget(golem);
        verify(fixture.second.guard, never()).setTarget(golem);
    }

    @Test
    void fallbackSweepAlsoPurgesASnowGolemTargetInjectedWithoutEvent() {
        GuardFixture fixture = new GuardFixture();
        manager.onCommand(fixture.owner, null, "garde", new String[0]);
        Snowman golem = snowGolem(fixture.world);
        when(golem.getTarget()).thenReturn(fixture.first.guard);
        when(fixture.owner.getNearbyEntities(32.0D, 32.0D, 32.0D))
                .thenReturn(List.<Entity>of(golem));

        manager.followActiveSquads();

        verify(golem).setTarget((LivingEntity) null);
        verify(golem).setAggressive(false);
        verify(fixture.first.guard, never()).setTarget(golem);
        verify(fixture.second.guard, never()).setTarget(golem);
    }

    private void assertSpawnRequest(SpawnRequest request, UUID ownerId, int slot) {
        assertEquals(ownerId, request.ownerId());
        assertEquals(slot, request.slot());
        assertEquals(100.0D, request.settings().maxHealth());
        assertEquals(16.0D, request.settings().attackDamage());
        assertEquals(0.35D, request.settings().movementSpeed());
        assertEquals(48.0D, request.settings().followRange());
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

    private Snowman snowGolem(World world) {
        Snowman golem = mock(Snowman.class);
        when(golem.getUniqueId()).thenReturn(UUID.randomUUID());
        when(golem.getWorld()).thenReturn(world);
        when(golem.getLocation()).thenReturn(new Location(world, 1.0D, 64.0D, 0.0D));
        when(golem.isValid()).thenReturn(true);
        when(golem.isDead()).thenReturn(false);
        return golem;
    }

    private Husk unmarkedHusk(World world) {
        Husk husk = mock(Husk.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(husk.getUniqueId()).thenReturn(UUID.randomUUID());
        when(husk.getWorld()).thenReturn(world);
        when(husk.getLocation()).thenReturn(new Location(world, 1.0D, 64.0D, 0.0D));
        when(husk.isValid()).thenReturn(true);
        when(husk.isDead()).thenReturn(false);
        when(husk.getPersistentDataContainer()).thenReturn(pdc);
        return husk;
    }

    private IronGolem ironGolem(World world) {
        IronGolem golem = mock(IronGolem.class);
        when(golem.getUniqueId()).thenReturn(UUID.randomUUID());
        when(golem.getWorld()).thenReturn(world);
        when(golem.getLocation()).thenReturn(new Location(world, 1.0D, 64.0D, 0.0D));
        when(golem.isValid()).thenReturn(true);
        when(golem.isDead()).thenReturn(false);
        return golem;
    }

    private Mob mob(World world) {
        Mob mob = mock(Mob.class);
        when(mob.getUniqueId()).thenReturn(UUID.randomUUID());
        when(mob.getWorld()).thenReturn(world);
        when(mob.getLocation()).thenReturn(new Location(world, 1.0D, 64.0D, 0.0D));
        when(mob.isValid()).thenReturn(true);
        when(mob.isDead()).thenReturn(false);
        return mob;
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
            when(owner.isDead()).thenReturn(false);
            when(owner.getName()).thenReturn("Testeur");
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
        when(guard.teleport(any(Location.class))).thenReturn(true);
        when(guard.isValid()).thenReturn(true);
        when(guard.isDead()).thenReturn(false);
        when(guard.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(Keys.royalGuardType(), PersistentDataType.STRING)).thenReturn(RoyalGuardManager.GUARD_TYPE);
        when(pdc.get(Keys.royalGuardOwner(), PersistentDataType.STRING)).thenReturn(ownerId.toString());
        when(pdc.get(Keys.royalGuardSlot(), PersistentDataType.INTEGER)).thenReturn(slot);
        return new GuardDouble(guard);
    }

    private static final class NavigationRecorder implements RoyalGuardManager.GuardNavigator {
        private final List<NavigationCall> calls = new ArrayList<>();
        private int stopCalls;

        @Override
        public boolean moveTo(Mob guard, Location target, double speed) {
            calls.add(new NavigationCall(guard.getUniqueId(), target.clone(), speed));
            return true;
        }

        @Override
        public void stop(Mob guard) {
            stopCalls++;
        }

        private int stopCalls() {
            return stopCalls;
        }

        private Location lastTargetFor(Mob guard) {
            for (int index = calls.size() - 1; index >= 0; index--) {
                NavigationCall call = calls.get(index);
                if (call.guardId().equals(guard.getUniqueId())) {
                    return call.target().clone();
                }
            }
            return null;
        }
    }

    private record NavigationCall(UUID guardId, Location target, double speed) {
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

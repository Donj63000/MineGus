package org.example.mineur;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.example.TeleportUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Single scheduled task that advances the miner session using a state machine.
 */
public final class MiningLoop extends BukkitRunnable {

    private static final double ANIMATION_STAGES = 2.0D;
    private static final double BREAK_STAGE = 1.0D;
    private static final double DEPOSIT_STAGE = 1.0D;

    public enum Phase {
        IDLE,
        ANIMATING,
        BREAKING,
        DEPOSITING
    }

    private final JavaPlugin plugin;
    private final MiningSessionState state;
    private final MiningIterator iterator;
    private final InventoryRouter router;
    private final Villager miner;
    private final Consumer<Block> decorationCallback;
    private final Runnable completionCallback;
    private final Runnable storageBlockedCallback;
    private final Runnable storageFreedCallback;
    private final double progressPerTick;
    private boolean storageBlockedNotified;

    private Block current;
    private Phase phase = Phase.IDLE;
    private double phaseProgress = 0.0D;

    public MiningLoop(JavaPlugin plugin,
                      MiningSessionState state,
                      MiningIterator iterator,
                      InventoryRouter router,
                      Villager miner,
                      Consumer<Block> decorationCallback,
                      Runnable completionCallback,
                      Runnable storageBlockedCallback,
                      Runnable storageFreedCallback,
                      boolean initiallyBlocked,
                      double progressPerTick) {
        this.plugin = plugin;
        this.state = state;
        this.iterator = iterator;
        this.router = router;
        this.miner = miner;
        this.decorationCallback = decorationCallback;
        this.completionCallback = completionCallback;
        this.storageBlockedCallback = storageBlockedCallback;
        this.storageFreedCallback = storageFreedCallback;
        this.storageBlockedNotified = initiallyBlocked;
        this.progressPerTick = Math.max(0.01D, progressPerTick);
    }

    @Override
    public void run() {
        if (state.paused) {
            return;
        }
        if (!checkStorageAvailability()) {
            return;
        }
        if (miner == null || miner.isDead()) {
            cancel();
            if (completionCallback != null) {
                completionCallback.run();
            }
            return;
        }

        switch (phase) {
            case IDLE -> handleIdle();
            case ANIMATING -> handleAnimating();
            case BREAKING -> handleBreaking();
            case DEPOSITING -> handleDepositing();
        }
    }

    private void handleIdle() {
        if (!iterator.hasNext()) {
            cancel();
            if (completionCallback != null) {
                completionCallback.run();
            }
            return;
        }
        current = iterator.next();
        if (current == null) {
            return;
        }
        phase = Phase.ANIMATING;
        phaseProgress = 0.0D;
        moveMinerTowards(current);
    }

    private void handleAnimating() {
        if (current == null) {
            phase = Phase.IDLE;
            phaseProgress = 0.0D;
            return;
        }

        World world = current.getWorld();
        Location blockCenter = current.getLocation().add(0.5, 0.5, 0.5);
        Location minerTarget = blockCenter.clone().add(0, 0.5, 0);
        TeleportUtils.safeTeleport(miner, minerTarget);

        if (!miner.isDead()) {
            Location look = miner.getLocation();
            look.setDirection(blockCenter.toVector().subtract(look.toVector()));
            miner.teleport(look);
            miner.swingMainHand();
        }

        Material type = current.getType();
        boolean ore = isOre(type);
        Particle particle = ore ? Particle.CRIT : Particle.BLOCK;
        Object data = ore ? null : current.getBlockData();

        world.spawnParticle(particle, blockCenter, 10, 0.3, 0.3, 0.3, 0.1, data);
        world.playSound(blockCenter,
                ore ? Sound.ENTITY_VILLAGER_WORK_TOOLSMITH : Sound.BLOCK_STONE_HIT,
                0.6F,
                1.0F);

        phaseProgress += progressPerTick;
        if (phaseProgress >= ANIMATION_STAGES) {
            phase = Phase.BREAKING;
            phaseProgress = 0.0D;
        }
    }

    private void handleBreaking() {
        if (current == null) {
            phase = Phase.IDLE;
            phaseProgress = 0.0D;
            return;
        }
        if (!checkStorageAvailability()) {
            return;
        }

        Material type = current.getType();
        if (type == Material.AIR || type == Material.BEDROCK) {
            phase = Phase.IDLE;
            phaseProgress = 0.0D;
            return;
        }

        if (!miner.isDead()) {
            miner.swingMainHand();
        }

        phaseProgress += progressPerTick;
        if (phaseProgress < BREAK_STAGE) {
            return;
        }

        World world = current.getWorld();
        Location loc = current.getLocation();
        List<ItemStack> drops = new ArrayList<>(current.getDrops());
        current.setType(Material.AIR, false);

        world.spawnParticle(
                Particle.BLOCK,
                loc.clone().add(0.5, 0.5, 0.5),
                20,
                0.3, 0.3, 0.3,
                0.1,
                type.createBlockData()
        );

        boolean ore = isOre(type);
        world.playSound(
                loc,
                ore ? Sound.ENTITY_VILLAGER_WORK_TOOLSMITH : Sound.BLOCK_STONE_BREAK,
                0.7F,
                1.0F
        );

        if (decorationCallback != null) {
            decorationCallback.accept(current);
        }

        if (router != null && !drops.isEmpty()) {
            List<ItemStack> leftovers = router.deposit(drops);
            if (!leftovers.isEmpty()) {
                for (ItemStack stack : leftovers) {
                    if (stack != null && stack.getAmount() > 0) {
                        world.dropItemNaturally(loc, stack);
                    }
                }
            }
        }

        phase = Phase.DEPOSITING;
        phaseProgress = 0.0D;
    }

    private void handleDepositing() {
        phaseProgress += progressPerTick;
        if (phaseProgress >= DEPOSIT_STAGE) {
            phase = Phase.IDLE;
            phaseProgress = 0.0D;
        }
    }

    private boolean checkStorageAvailability() {
        if (router == null || !router.hasTargets()) {
            if (storageBlockedNotified) {
                storageBlockedNotified = false;
                if (storageFreedCallback != null) {
                    storageFreedCallback.run();
                }
            }
            return true;
        }
        if (!router.hasFreeSpace()) {
            if (!storageBlockedNotified) {
                storageBlockedNotified = true;
                if (storageBlockedCallback != null) {
                    storageBlockedCallback.run();
                }
            }
            return false;
        }
        if (storageBlockedNotified) {
            storageBlockedNotified = false;
            if (storageFreedCallback != null) {
                storageFreedCallback.run();
            }
        }
        return true;
    }

    private void moveMinerTowards(Block target) {
        Location center = target.getLocation().add(0.5, 1.0, 0.5);
        boolean moved = false;
        try {
            moved = miner.getPathfinder().moveTo(center, 1.1);
        } catch (NoSuchMethodError ignored) {
            // Older API without pathfinder support.
        }
        if (!moved) {
            TeleportUtils.safeTeleport(miner, center);
        }
        state.minerY = miner.getLocation().getY();
    }

    private boolean isOre(Material type) {
        if (type == null) {
            return false;
        }
        String name = type.name();
        return name.endsWith("_ORE") || type == Material.ANCIENT_DEBRIS;
    }
}

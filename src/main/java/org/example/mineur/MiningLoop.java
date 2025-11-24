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
    private boolean storageBlockedNotified;

    private Block current;
    private Phase phase = Phase.IDLE;
    private int stage = 0;

    public MiningLoop(JavaPlugin plugin,
                      MiningSessionState state,
                      MiningIterator iterator,
                      InventoryRouter router,
                      Villager miner,
                      Consumer<Block> decorationCallback,
                      Runnable completionCallback,
                      Runnable storageBlockedCallback,
                      Runnable storageFreedCallback,
                      boolean initiallyBlocked) {
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
        stage = 0;
        moveMinerTowards(current);
    }

    private void handleAnimating() {
        if (current == null) {
            phase = Phase.IDLE;
            return;
        }

        World world = current.getWorld();
        Location blockCenter = current.getLocation().add(0.5, 0.5, 0.5);

        Location minerTarget = blockCenter.clone().add(0, 0.5, 0);
        TeleportUtils.safeTeleport(miner, minerTarget);

        if (miner != null && !miner.isDead()) {
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
        world.playSound(
                blockCenter,
                ore ? Sound.ENTITY_VILLAGER_WORK_TOOLSMITH : Sound.BLOCK_STONE_HIT,
                0.6f,
                1.0f
        );

        stage++;
        if (stage >= 2) {
            phase = Phase.BREAKING;
            stage = 0;
        }
    }

    private void handleBreaking() {
        if (current == null) {
            phase = Phase.IDLE;
            return;
        }
        if (!checkStorageAvailability()) {
            return;
        }
        World world = current.getWorld();
        Material type = current.getType();
        if (type == Material.AIR || type == Material.BEDROCK) {
            phase = Phase.IDLE;
            return;
        }

        if (miner != null && !miner.isDead()) {
            miner.swingMainHand();
        }

        Location loc = current.getLocation();
        List<ItemStack> drops = new ArrayList<>(current.getDrops());
        current.setType(Material.AIR, false);

        world.spawnParticle(
                Particle.BLOCK,
                loc.add(0.5, 0.5, 0.5),
                20,
                0.3, 0.3, 0.3,
                0.1,
                type.createBlockData()
        );

        boolean ore = isOre(type);
        world.playSound(
                loc,
                ore ? Sound.ENTITY_VILLAGER_WORK_TOOLSMITH : Sound.BLOCK_STONE_BREAK,
                0.7f,
                1.0f
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
        stage = 0;
    }

    private void handleDepositing() {
        phase = Phase.IDLE;
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
        if (name.endsWith("_ORE")) {
            return true;
        }
        return type == Material.ANCIENT_DEBRIS;
    }
}

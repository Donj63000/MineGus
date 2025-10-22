package org.example.mineur;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
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

    private Block current;
    private Phase phase = Phase.IDLE;
    private int stage = 0;

    public MiningLoop(JavaPlugin plugin,
                      MiningSessionState state,
                      MiningIterator iterator,
                      InventoryRouter router,
                      Villager miner,
                      Consumer<Block> decorationCallback,
                      Runnable completionCallback) {
        this.plugin = plugin;
        this.state = state;
        this.iterator = iterator;
        this.router = router;
        this.miner = miner;
        this.decorationCallback = decorationCallback;
        this.completionCallback = completionCallback;
    }

    @Override
    public void run() {
        if (state.paused) {
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
        showMiningEffects(current, stage);
        try {
            miner.swingMainHand();
        } catch (NoSuchMethodError ignored) {
            // API without swing animation method.
        }
        stage++;
        if (stage >= 10) {
            phase = Phase.BREAKING;
        }
    }

    private void handleBreaking() {
        if (current == null) {
            phase = Phase.IDLE;
            return;
        }
        World world = current.getWorld();
        Material type = current.getType();
        if (type == Material.AIR || type == Material.BEDROCK) {
            phase = Phase.IDLE;
            return;
        }

        List<ItemStack> drops = new ArrayList<>(current.getDrops(new ItemStack(Material.IRON_PICKAXE)));
        current.setType(Material.AIR, false);

        if (!drops.isEmpty()) {
            phase = Phase.DEPOSITING;
            List<ItemStack> leftover = router.deposit(drops);
            if (!leftover.isEmpty()) {
                router.dropOnGround(world, current.getLocation().add(0.5, 0.5, 0.5), leftover);
            }
        } else {
            phase = Phase.IDLE;
        }
        if (decorationCallback != null) {
            decorationCallback.accept(current);
        }
    }

    private void handleDepositing() {
        phase = Phase.IDLE;
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

    private void showMiningEffects(Block block, int tickStage) {
        Location loc = block.getLocation().add(0.5, 0.5, 0.5);
        World world = block.getWorld();
        world.playSound(loc, Sound.BLOCK_STONE_BREAK, 0.6f, 1.0f);
        world.spawnParticle(Particle.BLOCK, loc, 8, 0.2, 0.2, 0.2, block.getBlockData());
        float progress = Math.min(1f, Math.max(0f, tickStage / 9f));
        for (Player player : world.getPlayers()) {
            player.sendBlockDamage(block.getLocation(), progress);
        }
    }
}

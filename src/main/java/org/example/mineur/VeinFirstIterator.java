package org.example.mineur;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * Iterator for the VEIN_FIRST pattern.
 *
 * The wrapped iterator keeps the normal quarry progression. Whenever an ore is
 * found near the current progression, this iterator mines the complete connected
 * vein first, then resumes the normal route. Vein search is bounded by radius
 * and maxBlocks to avoid expensive scans on the main server thread.
 */
public final class VeinFirstIterator implements MiningIterator {

    private static final int[][] NEIGHBOURS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };

    private final World world;
    private final MiningIterator delegate;
    private final int scanRadius;
    private final int maxVeinBlocks;
    private final ArrayDeque<Block> veinQueue = new ArrayDeque<>();
    private final Set<Long> queuedOrMined = new HashSet<>();
    private Block delayedNormalBlock;

    public VeinFirstIterator(World world, MiningIterator delegate, int scanRadius, int maxVeinBlocks) {
        this.world = world;
        this.delegate = delegate;
        this.scanRadius = Math.max(0, scanRadius);
        this.maxVeinBlocks = Math.max(1, maxVeinBlocks);
    }

    @Override
    public MiningCursor cursor() {
        return delegate.cursor();
    }

    @Override
    public boolean hasNext() {
        purgeInvalidQueuedBlocks();
        return isMineableBlock(delayedNormalBlock) || !veinQueue.isEmpty() || delegate.hasNext();
    }

    @Override
    public Block next() {
        Block queued = pollValidQueuedBlock();
        if (queued != null) {
            return queued;
        }

        Block delayed = pollDelayedNormalBlock();
        if (delayed != null) {
            return delayed;
        }

        while (delegate.hasNext()) {
            Block normal = delegate.next();
            if (normal == null) {
                return null;
            }
            if (!isMineable(normal.getType())) {
                continue;
            }

            Block nearbyOre = findNearestOre(normal);
            if (nearbyOre != null) {
                if (!sameBlock(nearbyOre, normal)) {
                    delayedNormalBlock = normal;
                }
                enqueueVein(nearbyOre);
                Block veinBlock = pollValidQueuedBlock();
                if (veinBlock != null) {
                    return veinBlock;
                }
                Block delayedAfterEmptyVein = pollDelayedNormalBlock();
                if (delayedAfterEmptyVein != null) {
                    return delayedAfterEmptyVein;
                }
            }

            return normal;
        }
        return null;
    }

    private Block pollValidQueuedBlock() {
        while (!veinQueue.isEmpty()) {
            Block block = veinQueue.pollFirst();
            if (block != null && isOre(block.getType())) {
                queuedOrMined.add(key(block.getX(), block.getY(), block.getZ()));
                return block;
            }
        }
        return null;
    }

    private void purgeInvalidQueuedBlocks() {
        while (!veinQueue.isEmpty()) {
            Block block = veinQueue.peekFirst();
            if (block != null && isOre(block.getType())) {
                return;
            }
            veinQueue.pollFirst();
        }
    }

    private Block pollDelayedNormalBlock() {
        Block delayed = delayedNormalBlock;
        delayedNormalBlock = null;
        if (isMineableBlock(delayed)) {
            return delayed;
        }
        return null;
    }

    private Block findNearestOre(Block origin) {
        if (origin == null) {
            return null;
        }
        if (isOre(origin.getType())) {
            return origin;
        }
        if (scanRadius <= 0) {
            return null;
        }

        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        int radiusSquared = scanRadius * scanRadius;

        List<Block> candidates = new ArrayList<>();
        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dy = -scanRadius; dy <= scanRadius; dy++) {
                int y = oy + dy;
                if (y < minY || y > maxY) {
                    continue;
                }
                for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                    int distanceSquared = dx * dx + dy * dy + dz * dz;
                    if (distanceSquared > radiusSquared) {
                        continue;
                    }
                    int x = ox + dx;
                    int z = oz + dz;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }
                    long key = key(x, y, z);
                    if (queuedOrMined.contains(key)) {
                        continue;
                    }
                    Block block = world.getBlockAt(x, y, z);
                    if (isOre(block.getType())) {
                        candidates.add(block);
                    }
                }
            }
        }

        return candidates.stream()
                .min(Comparator.comparingInt(block -> distanceSquared(origin, block)))
                .orElse(null);
    }

    private void enqueueVein(Block seed) {
        if (seed == null || !isOre(seed.getType())) {
            return;
        }

        String group = oreGroup(seed.getType());
        Queue<Block> open = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        int radiusSquared = scanRadius * scanRadius;
        open.add(seed);
        visited.add(key(seed.getX(), seed.getY(), seed.getZ()));

        while (!open.isEmpty() && veinQueue.size() < maxVeinBlocks) {
            Block block = open.poll();
            Material type = block.getType();
            if (!isOre(type) || !oreGroup(type).equals(group)) {
                continue;
            }

            long blockKey = key(block.getX(), block.getY(), block.getZ());
            if (!queuedOrMined.contains(blockKey)) {
                queuedOrMined.add(blockKey);
                veinQueue.addLast(block);
            }

            for (int[] offset : NEIGHBOURS) {
                int nx = block.getX() + offset[0];
                int ny = block.getY() + offset[1];
                int nz = block.getZ() + offset[2];
                if (ny < world.getMinHeight() || ny >= world.getMaxHeight()) {
                    continue;
                }
                if (distanceSquared(seed.getX(), seed.getY(), seed.getZ(), nx, ny, nz) > radiusSquared) {
                    continue;
                }
                if (!world.isChunkLoaded(nx >> 4, nz >> 4)) {
                    continue;
                }
                long neighbourKey = key(nx, ny, nz);
                if (!visited.add(neighbourKey) || queuedOrMined.contains(neighbourKey)) {
                    continue;
                }
                Block neighbour = world.getBlockAt(nx, ny, nz);
                if (isOre(neighbour.getType()) && oreGroup(neighbour.getType()).equals(group)) {
                    open.add(neighbour);
                }
            }
        }
    }

    private boolean isMineableBlock(Block block) {
        return block != null && isMineable(block.getType());
    }

    private boolean isMineable(Material type) {
        return type != null && !isAir(type) && type != Material.BEDROCK;
    }

    private boolean sameBlock(Block first, Block second) {
        return first != null && second != null
                && first.getWorld().equals(second.getWorld())
                && first.getX() == second.getX()
                && first.getY() == second.getY()
                && first.getZ() == second.getZ();
    }

    private boolean isOre(Material type) {
        if (type == null) {
            return false;
        }
        String name = type.name();
        return name.endsWith("_ORE") || type == Material.ANCIENT_DEBRIS;
    }

    private boolean isAir(Material type) {
        return type == Material.AIR
                || type == Material.CAVE_AIR
                || type == Material.VOID_AIR;
    }

    private String oreGroup(Material type) {
        String name = type.name();
        if (name.startsWith("DEEPSLATE_")) {
            return name.substring("DEEPSLATE_".length());
        }
        return name;
    }

    private int distanceSquared(Block first, Block second) {
        return distanceSquared(first.getX(), first.getY(), first.getZ(), second.getX(), second.getY(), second.getZ());
    }

    private int distanceSquared(int x1, int y1, int z1, int x2, int y2, int z2) {
        int dx = x1 - x2;
        int dy = y1 - y2;
        int dz = z1 - z2;
        return dx * dx + dy * dy + dz * dz;
    }

    private long key(int x, int y, int z) {
        long lx = ((long) x & 0x3FFFFFFL) << 38;
        long lz = ((long) z & 0x3FFFFFFL) << 12;
        long ly = (long) y & 0xFFFL;
        return lx | lz | ly;
    }
}

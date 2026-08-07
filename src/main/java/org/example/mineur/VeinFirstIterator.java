package org.example.mineur;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/**
 * Priorise les veines de minerai proches du parcours principal.
 *
 * <p>Le parcours délégué est remis à son checkpoint pendant l'extraction d'une
 * veine. Ainsi, une pause ou un redémarrage ne peut pas faire disparaître le
 * bloc normal qui devait être traité juste après la veine.</p>
 */
public final class VeinFirstIterator implements MiningIterator {

    private static final int[][] NEIGHBOURS = createNeighbours();

    private final World world;
    private final MiningIterator delegate;
    private final int scanRadius;
    private final int maxVeinBlocks;
    private final int scanEveryBlocks;
    private final boolean restrictToCursorBounds;
    private final int minAllowedX;
    private final int maxAllowedX;
    private final int minAllowedY;
    private final int maxAllowedY;
    private final int minAllowedZ;
    private final int maxAllowedZ;

    private final ArrayDeque<Block> veinQueue = new ArrayDeque<>();
    /**
     * Uniquement les blocs actuellement présents dans la file.
     *
     * <p>Conserver toutes les coordonnées déjà minées pendant toute une
     * carrière faisait croître ce set sans limite. Un bloc retiré de la file
     * est désormais oublié : s'il est déjà miné, son type AIR suffit à
     * empêcher sa redécouverte.</p>
     */
    private final Set<Long> queuedKeys = new HashSet<>();
    private int normalBlocksSinceScan;

    /*
     * Le bloc normal est différé en mémoire tandis que le curseur public reste
     * sur son checkpoint. En cas de crash, la sauvegarde rejouera donc la
     * détection au lieu de sauter ce bloc.
     */
    private Block deferredNormal;
    private MiningCursor deferredCheckpoint;
    private MiningCursor deferredResumeCursor;

    public VeinFirstIterator(World world,
                             MiningIterator delegate,
                             int scanRadius,
                             int maxVeinBlocks) {
        this(world, delegate, scanRadius, maxVeinBlocks, 1,
                world.getMinHeight(), world.getMaxHeight() - 1, false);
    }

    public VeinFirstIterator(World world,
                             MiningIterator delegate,
                             int scanRadius,
                             int maxVeinBlocks,
                             int scanEveryBlocks,
                             int minAllowedY,
                             int maxAllowedY,
                             boolean restrictToCursorBounds) {
        this.world = world;
        this.delegate = delegate;
        this.scanRadius = Math.max(0, scanRadius);
        this.maxVeinBlocks = Math.max(1, maxVeinBlocks);
        this.scanEveryBlocks = Math.max(1, scanEveryBlocks);
        this.restrictToCursorBounds = restrictToCursorBounds;

        MiningCursor bounds = delegate.cursor().copy();
        this.minAllowedX = bounds.minX;
        this.maxAllowedX = safeInclusiveEnd(bounds.minX, bounds.width);
        this.minAllowedZ = bounds.minZ;
        this.maxAllowedZ = safeInclusiveEnd(bounds.minZ, bounds.length);
        this.minAllowedY = Math.max(world.getMinHeight(), Math.min(minAllowedY, maxAllowedY));
        this.maxAllowedY = Math.min(world.getMaxHeight() - 1, Math.max(minAllowedY, maxAllowedY));
    }

    @Override
    public MiningCursor cursor() {
        return delegate.cursor();
    }

    @Override
    public boolean hasNext() {
        purgeInvalidQueuedBlocks();
        return !veinQueue.isEmpty()
                || deferredNormal != null
                || delegate.hasNext();
    }

    @Override
    public Block next() {
        Block queued = pollValidQueuedBlock();
        if (queued != null) {
            keepDeferredCheckpoint();
            return queued;
        }

        Block delayed = consumeDeferredNormal();
        if (delayed != null) {
            return delayed;
        }

        if (!delegate.hasNext()) {
            return null;
        }

        MiningCursor checkpoint = delegate.cursor().copy();
        Block normal = delegate.next();
        if (normal == null || !MiningBlockPolicy.isCandidate(normal.getType())) {
            return null;
        }
        MiningCursor resumeCursor = delegate.cursor().copy();

        boolean mustScan = MiningBlockPolicy.isOre(normal.getType());
        normalBlocksSinceScan++;
        if (!mustScan && normalBlocksSinceScan >= scanEveryBlocks) {
            mustScan = true;
        }

        if (!mustScan) {
            return normal;
        }
        normalBlocksSinceScan = 0;

        Block nearbyOre = findNearestOre(normal);
        if (nearbyOre == null) {
            return normal;
        }

        enqueueVein(nearbyOre);
        Block veinBlock = pollValidQueuedBlock();
        if (veinBlock == null) {
            return normal;
        }

        deferredNormal = normal;
        deferredCheckpoint = checkpoint;
        deferredResumeCursor = resumeCursor;
        keepDeferredCheckpoint();
        return veinBlock;
    }

    private void keepDeferredCheckpoint() {
        if (deferredCheckpoint != null) {
            delegate.cursor().copyFrom(deferredCheckpoint);
        }
    }

    private Block consumeDeferredNormal() {
        if (deferredNormal == null) {
            return null;
        }

        Block result = deferredNormal;
        MiningCursor resume = deferredResumeCursor;
        deferredNormal = null;
        deferredCheckpoint = null;
        deferredResumeCursor = null;

        if (resume != null) {
            delegate.cursor().copyFrom(resume);
        }
        return MiningBlockPolicy.isCandidate(result.getType()) ? result : null;
    }

    private Block pollValidQueuedBlock() {
        while (!veinQueue.isEmpty()) {
            Block block = veinQueue.pollFirst();
            forgetQueuedBlock(block);
            if (block != null
                    && withinBounds(block.getX(), block.getY(), block.getZ())
                    && MiningBlockPolicy.isOre(block.getType())) {
                return block;
            }
        }
        return null;
    }

    private void purgeInvalidQueuedBlocks() {
        while (!veinQueue.isEmpty()) {
            Block block = veinQueue.peekFirst();
            if (block != null
                    && withinBounds(block.getX(), block.getY(), block.getZ())
                    && MiningBlockPolicy.isOre(block.getType())) {
                return;
            }
            forgetQueuedBlock(veinQueue.pollFirst());
        }
    }

    private void forgetQueuedBlock(Block block) {
        if (block != null) {
            queuedKeys.remove(key(block.getX(), block.getY(), block.getZ()));
        }
    }

    private Block findNearestOre(Block origin) {
        if (origin == null) {
            return null;
        }
        if (MiningBlockPolicy.isOre(origin.getType())) {
            return origin;
        }
        if (scanRadius <= 0) {
            return null;
        }

        Block nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        int radiusSquared = scanRadius * scanRadius;

        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dy = -scanRadius; dy <= scanRadius; dy++) {
                for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                    int distanceSquared = dx * dx + dy * dy + dz * dz;
                    if (distanceSquared > radiusSquared || distanceSquared >= nearestDistance) {
                        continue;
                    }

                    int x = origin.getX() + dx;
                    int y = origin.getY() + dy;
                    int z = origin.getZ() + dz;
                    if (!withinBounds(x, y, z)
                            || !world.isChunkLoaded(x >> 4, z >> 4)
                            || queuedKeys.contains(key(x, y, z))) {
                        continue;
                    }

                    Block block = world.getBlockAt(x, y, z);
                    if (MiningBlockPolicy.isOre(block.getType())) {
                        nearest = block;
                        nearestDistance = distanceSquared;
                    }
                }
            }
        }
        return nearest;
    }

    private void enqueueVein(Block seed) {
        if (seed == null || !MiningBlockPolicy.isOre(seed.getType())) {
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
            if (!MiningBlockPolicy.isOre(type) || !oreGroup(type).equals(group)) {
                continue;
            }

            long blockKey = key(block.getX(), block.getY(), block.getZ());
            if (queuedKeys.add(blockKey)) {
                veinQueue.addLast(block);
            }

            for (int[] offset : NEIGHBOURS) {
                int x = block.getX() + offset[0];
                int y = block.getY() + offset[1];
                int z = block.getZ() + offset[2];
                if (!withinBounds(x, y, z)
                        || distanceSquared(seed.getX(), seed.getY(), seed.getZ(), x, y, z) > radiusSquared
                        || !world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }

                long neighbourKey = key(x, y, z);
                if (!visited.add(neighbourKey) || queuedKeys.contains(neighbourKey)) {
                    continue;
                }

                Block neighbour = world.getBlockAt(x, y, z);
                if (MiningBlockPolicy.isOre(neighbour.getType())
                        && oreGroup(neighbour.getType()).equals(group)) {
                    open.add(neighbour);
                }
            }
        }
    }

    private boolean withinBounds(int x, int y, int z) {
        if (y < minAllowedY || y > maxAllowedY) {
            return false;
        }
        return !restrictToCursorBounds
                || (x >= minAllowedX && x <= maxAllowedX
                && z >= minAllowedZ && z <= maxAllowedZ);
    }

    private String oreGroup(Material type) {
        String name = type.name();
        return name.startsWith("DEEPSLATE_")
                ? name.substring("DEEPSLATE_".length())
                : name;
    }

    private int distanceSquared(int x1, int y1, int z1, int x2, int y2, int z2) {
        int dx = x1 - x2;
        int dy = y1 - y2;
        int dz = z1 - z2;
        return dx * dx + dy * dy + dz * dz;
    }

    private long key(int x, int y, int z) {
        long packedX = ((long) x & 0x3FFFFFFL) << 38;
        long packedZ = ((long) z & 0x3FFFFFFL) << 12;
        long packedY = (long) y & 0xFFFL;
        return packedX | packedZ | packedY;
    }

    private static int safeInclusiveEnd(int start, int size) {
        long end = (long) start + Math.max(1, size) - 1L;
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, end));
    }

    private static int[][] createNeighbours() {
        int[][] neighbours = new int[26][3];
        int index = 0;
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }
                    neighbours[index++] = new int[]{x, y, z};
                }
            }
        }
        return neighbours;
    }
}

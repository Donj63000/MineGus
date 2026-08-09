package org.example.tutorial;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Random;

/**
 * Générateur léger du monde de visite.
 *
 * <p>La zone centrale est une grande plaine parfaitement stable pour que les
 * scènes guidées restent alignées. À distance, une transition abrupte forme
 * des plateaux et falaises de pierre visibles depuis tout le parcours.</p>
 *
 * <p>Toutes les fonctions sont pures : Paper peut donc générer plusieurs chunks
 * en parallèle sans verrou ni accès à l'API monde.</p>
 */
public final class TutorialWorldGenerator extends ChunkGenerator {

    public static final int GROUND_Y = 72;
    public static final int WALK_Y = GROUND_Y + 1;

    private static final int PLAIN_MIN_X = -90;
    private static final int PLAIN_MAX_X = 300;
    private static final int PLAIN_HALF_Z = 100;
    private static final int CLIFF_TRANSITION_START = 28;
    private static final int CLIFF_TRANSITION_END = 78;

    @Override
    public void generateNoise(
            @NotNull WorldInfo worldInfo,
            @NotNull Random random,
            int chunkX,
            int chunkZ,
            @NotNull ChunkData chunkData) {
        int minY = chunkData.getMinHeight();
        int maxY = chunkData.getMaxHeight();
        long seed = worldInfo.getSeed();

        for (int localX = 0; localX < 16; localX++) {
            int worldX = (chunkX << 4) + localX;
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldZ = (chunkZ << 4) + localZ;
                int surfaceY = Math.max(
                        minY + 6,
                        Math.min(maxY - 2, surfaceY(seed, worldX, worldZ))
                );

                chunkData.setBlock(localX, minY, localZ, Material.BEDROCK);

                int deepslateTop = Math.min(-7, surfaceY - 5);
                if (minY + 1 < deepslateTop) {
                    chunkData.setRegion(
                            localX,
                            minY + 1,
                            localZ,
                            localX + 1,
                            deepslateTop,
                            localZ + 1,
                            Material.DEEPSLATE
                    );
                }

                int stoneStart = Math.max(minY + 1, deepslateTop);
                int stoneTop = Math.max(stoneStart, surfaceY - 3);
                if (stoneStart < stoneTop) {
                    chunkData.setRegion(
                            localX,
                            stoneStart,
                            localZ,
                            localX + 1,
                            stoneTop,
                            localZ + 1,
                            Material.STONE
                    );
                }

                if (isCliffZone(worldX, worldZ)) {
                    Material cap = cliffCap(seed, worldX, worldZ);
                    chunkData.setRegion(
                            localX,
                            Math.max(minY + 1, surfaceY - 3),
                            localZ,
                            localX + 1,
                            surfaceY + 1,
                            localZ + 1,
                            cap
                    );
                } else {
                    chunkData.setRegion(
                            localX,
                            Math.max(minY + 1, surfaceY - 3),
                            localZ,
                            localX + 1,
                            surfaceY,
                            localZ + 1,
                            Material.DIRT
                    );
                    chunkData.setBlock(
                            localX,
                            surfaceY,
                            localZ,
                            Material.GRASS_BLOCK
                    );
                }
            }
        }
    }

    @Override
    public void generateSurface(
            @NotNull WorldInfo worldInfo,
            @NotNull Random random,
            int chunkX,
            int chunkZ,
            @NotNull ChunkData chunkData) {
        long seed = worldInfo.getSeed();
        int maxY = chunkData.getMaxHeight();

        for (int localX = 0; localX < 16; localX++) {
            int worldX = (chunkX << 4) + localX;
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldZ = (chunkZ << 4) + localZ;
                int surfaceY = surfaceY(seed, worldX, worldZ);
                if (surfaceY + 1 >= maxY
                        || isCliffZone(worldX, worldZ)) {
                    continue;
                }

                long hash = mix(seed, worldX, worldZ);
                int selector = (int) Math.floorMod(hash, 211L);
                Material decoration = switch (selector) {
                    case 0, 1, 2, 3 -> Material.SHORT_GRASS;
                    case 4 -> Material.DANDELION;
                    case 5 -> Material.POPPY;
                    case 6 -> Material.CORNFLOWER;
                    case 7 -> Material.OXEYE_DAISY;
                    default -> null;
                };

                if (decoration != null) {
                    chunkData.setBlock(
                            localX,
                            surfaceY + 1,
                            localZ,
                            decoration
                    );
                }
            }
        }
    }

    @Override
    public int getBaseHeight(
            @NotNull WorldInfo worldInfo,
            @NotNull Random random,
            int x,
            int z,
            @NotNull HeightMap heightMap) {
        return surfaceY(worldInfo.getSeed(), x, z) + 1;
    }

    @Override
    public Location getFixedSpawnLocation(
            @NotNull World world,
            @NotNull Random random) {
        return new Location(world, -24.5D, WALK_Y, -42.5D, -90.0F, 0.0F);
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(
            @NotNull WorldInfo worldInfo) {
        return TutorialBiomeProviderHolder.INSTANCE;
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    /**
     * Hauteur déterministe exposée aux tests et au constructeur de scènes.
     */
    static int surfaceY(long seed, int x, int z) {
        double outside = distanceOutsidePlain(x, z);
        if (outside <= 0.0D) {
            return GROUND_Y;
        }

        double lowNoise = fractalNoise(seed ^ 0x5DEECE66DL, x, z);
        double detailNoise = fractalNoise(seed ^ 0x9E3779B97F4A7C15L,
                x * 2, z * 2);

        double gentle = smoothStep(0.0D, CLIFF_TRANSITION_START, outside)
                * (2.0D + lowNoise * 2.5D);
        double cliff = smoothStep(
                CLIFF_TRANSITION_START,
                CLIFF_TRANSITION_END,
                outside
        );
        double ridge = cliff * (
                31.0D
                        + lowNoise * 13.0D
                        + detailNoise * 5.0D
        );

        return GROUND_Y + (int) Math.round(gentle + ridge);
    }

    static boolean isPlainCoordinate(int x, int z) {
        return x >= PLAIN_MIN_X
                && x <= PLAIN_MAX_X
                && Math.abs(z) <= PLAIN_HALF_Z;
    }

    private static boolean isCliffZone(int x, int z) {
        return distanceOutsidePlain(x, z) >= CLIFF_TRANSITION_START;
    }

    private static Material cliffCap(long seed, int x, int z) {
        int selector = (int) Math.floorMod(mix(seed, x, z), 13L);
        return switch (selector) {
            case 0 -> Material.ANDESITE;
            case 1 -> Material.CALCITE;
            case 2 -> Material.GRAVEL;
            default -> Material.STONE;
        };
    }

    private static double distanceOutsidePlain(int x, int z) {
        double dx = x < PLAIN_MIN_X
                ? PLAIN_MIN_X - x
                : Math.max(0.0D, x - PLAIN_MAX_X);
        double dz = Math.max(0.0D, Math.abs((double) z) - PLAIN_HALF_Z);
        return Math.hypot(dx, dz);
    }

    /**
     * Bruit de valeur multi-octaves, borné approximativement entre -1 et 1.
     */
    private static double fractalNoise(long seed, int x, int z) {
        double total = 0.0D;
        double amplitude = 1.0D;
        double amplitudeSum = 0.0D;
        double scale = 96.0D;

        for (int octave = 0; octave < 4; octave++) {
            total += valueNoise(seed + octave * 31L, x / scale, z / scale)
                    * amplitude;
            amplitudeSum += amplitude;
            amplitude *= 0.5D;
            scale *= 0.5D;
        }
        return total / amplitudeSum;
    }

    private static double valueNoise(long seed, double x, double z) {
        int x0 = fastFloor(x);
        int z0 = fastFloor(z);
        int x1 = x0 + 1;
        int z1 = z0 + 1;

        double tx = fade(x - x0);
        double tz = fade(z - z0);

        double a = randomUnit(seed, x0, z0);
        double b = randomUnit(seed, x1, z0);
        double c = randomUnit(seed, x0, z1);
        double d = randomUnit(seed, x1, z1);

        double ab = lerp(a, b, tx);
        double cd = lerp(c, d, tx);
        return lerp(ab, cd, tz);
    }

    private static double randomUnit(long seed, int x, int z) {
        long mixed = mix(seed, x, z);
        double normalized = (mixed >>> 11) * 0x1.0p-53;
        return normalized * 2.0D - 1.0D;
    }

    private static long mix(long seed, int x, int z) {
        long value = seed;
        value ^= (long) x * 0x9E3779B97F4A7C15L;
        value = Long.rotateLeft(value, 27);
        value ^= (long) z * 0xC2B2AE3D27D4EB4FL;
        value ^= value >>> 33;
        value *= 0xFF51AFD7ED558CCDL;
        value ^= value >>> 33;
        value *= 0xC4CEB9FE1A85EC53L;
        return value ^ (value >>> 33);
    }

    private static int fastFloor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    private static double fade(double value) {
        return value * value * (3.0D - 2.0D * value);
    }

    private static double lerp(double start, double end, double amount) {
        return start + (end - start) * amount;
    }

    private static double smoothStep(
            double edge0,
            double edge1,
            double value) {
        if (edge1 <= edge0) {
            return value >= edge1 ? 1.0D : 0.0D;
        }
        double normalized = Math.max(
                0.0D,
                Math.min(1.0D, (value - edge0) / (edge1 - edge0))
        );
        return normalized * normalized * (3.0D - 2.0D * normalized);
    }

    private static final class TutorialBiomeProvider
            extends BiomeProvider {

        private static final List<Biome> BIOMES =
                List.of(Biome.PLAINS, Biome.MEADOW);

        @Override
        public @NotNull Biome getBiome(
                @NotNull WorldInfo worldInfo,
                int x,
                int y,
                int z) {
            return isCliffZone(x, z) ? Biome.MEADOW : Biome.PLAINS;
        }

        @Override
        public @NotNull List<Biome> getBiomes(
                @NotNull WorldInfo worldInfo) {
            return BIOMES;
        }
    }

    private static final class TutorialBiomeProviderHolder {

        private static final BiomeProvider INSTANCE =
                new TutorialBiomeProvider();
    }
}

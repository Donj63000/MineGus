package org.example.village;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Helpers communs pour donner une direction visuelle medievale coherente
 * aux maisons, batiments speciaux et murailles.
 */
public final class VillageStyle {

    public record Palette(
            Material foundationPrimary,
            Material foundationAccent,
            Material paving,
            Material timber,
            Material wallFill,
            Material floor,
            Material roofBlock,
            Material roofStairs,
            Material roofSlab,
            Material door,
            Material shutter,
            Material fence,
            Material awning,
            Material detail,
            Material window
    ) {}

    private static final List<Material> ACCENT_PLANKS = List.of(
            Material.SPRUCE_PLANKS,
            Material.DARK_OAK_PLANKS
    );

    private VillageStyle() {}

    public static Material pickAccentPlanks(Random rng) {
        Random random = rng != null ? rng : new Random();
        return ACCENT_PLANKS.get(random.nextInt(ACCENT_PLANKS.size()));
    }

    public static Palette medievalPalette(Material accentPlanks) {
        Material accent = accentPlanks != null ? accentPlanks : Material.SPRUCE_PLANKS;
        Material roofStairs = stairsFrom(accent);
        Material roofSlab = slabFrom(accent);
        Material door = materialFrom(accent, "_PLANKS", "_DOOR", Material.SPRUCE_DOOR);
        Material shutter = materialFrom(accent, "_PLANKS", "_TRAPDOOR", Material.SPRUCE_TRAPDOOR);
        Material fence = materialFrom(accent, "_PLANKS", "_FENCE", Material.SPRUCE_FENCE);
        return new Palette(
                Material.STONE_BRICKS,
                Material.COBBLESTONE,
                Material.POLISHED_ANDESITE,
                accent == Material.DARK_OAK_PLANKS ? Material.STRIPPED_DARK_OAK_LOG : Material.STRIPPED_SPRUCE_LOG,
                Material.BIRCH_PLANKS,
                Material.OAK_PLANKS,
                accent,
                roofStairs,
                roofSlab,
                door,
                shutter,
                fence,
                roofStairs,
                Material.STONE_BRICK_WALL,
                Material.GLASS_PANE
        );
    }

    public static int[] offset(BlockFace face) {
        return new int[]{face.getModX(), face.getModZ()};
    }

    public static BlockFace opposite(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.NORTH;
            case EAST -> BlockFace.WEST;
            case WEST -> BlockFace.EAST;
            default -> BlockFace.NORTH;
        };
    }

    public static BlockFace leftOf(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.WEST;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            case WEST -> BlockFace.SOUTH;
            default -> BlockFace.WEST;
        };
    }

    public static BlockFace rightOf(BlockFace face) {
        return opposite(leftOf(face));
    }

    public static void setDoor(World world, int x, int y, int z, Material material, BlockFace facing, Bisected.Half half) {
        if (world == null) {
            return;
        }
        Door door = (Door) material.createBlockData();
        door.setFacing(facing);
        door.setHalf(half);
        world.getBlockAt(x, y, z).setBlockData(door, false);
    }

    public static void setTrapdoor(World world, int x, int y, int z, Material material, BlockFace facing, boolean open) {
        if (world == null) {
            return;
        }
        TrapDoor trapDoor = (TrapDoor) material.createBlockData();
        trapDoor.setFacing(facing);
        trapDoor.setOpen(open);
        world.getBlockAt(x, y, z).setBlockData(trapDoor, false);
    }

    public static void setStair(World world, int x, int y, int z,
                                Material material, BlockFace facing,
                                Stairs.Half half, Stairs.Shape shape) {
        if (world == null) {
            return;
        }
        Stairs stairs = (Stairs) material.createBlockData();
        stairs.setFacing(facing);
        stairs.setHalf(half);
        stairs.setShape(shape);
        world.getBlockAt(x, y, z).setBlockData(stairs, false);
    }

    public static void setDirectional(World world, int x, int y, int z, Material material, BlockFace facing) {
        if (world == null) {
            return;
        }
        Directional directional = (Directional) material.createBlockData();
        directional.setFacing(facing);
        world.getBlockAt(x, y, z).setBlockData(directional, false);
    }

    public static Material stairsFrom(Material planks) {
        return materialFrom(planks, "_PLANKS", "_STAIRS", Material.SPRUCE_STAIRS);
    }

    public static Material slabFrom(Material planks) {
        return materialFrom(planks, "_PLANKS", "_SLAB", Material.SPRUCE_SLAB);
    }

    private static Material materialFrom(Material source, String from, String to, Material fallback) {
        try {
            return Material.valueOf(source.name().replace(from, to).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}

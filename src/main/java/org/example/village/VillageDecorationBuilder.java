package org.example.village;

import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.example.village.VillageLayoutPlan.Bounds;
import static org.example.village.VillageLayoutPlan.LotPlan;
import static org.example.village.VillageLayoutPlan.LotRole;
import static org.example.village.VillageLayoutPlan.StreetPlan;

/**
 * Remplit les interstices du village sans empiéter sur les rues ni les lots.
 *
 * <p>Un village crédible n'est pas uniquement une collection de bâtiments :
 * il possède des usages secondaires, des réserves, des arbres, du linge, des
 * charrettes et de petits lieux de pause. Cette couche décorative travaille
 * avec une carte d'occupation explicite, ce qui évite les arbres dans les
 * toitures et les accessoires au milieu des chaussées.</p>
 */
public final class VillageDecorationBuilder {

    private VillageDecorationBuilder() {}

    public static List<Runnable> build(World world,
                                       VillageLayoutPlan layout,
                                       VillageLayoutSettings settings,
                                       int baseY,
                                       TerrainManager.SetBlock setBlock,
                                       Random rng) {
        List<Runnable> tasks = new ArrayList<>();
        if (layout == null || settings == null || setBlock == null) {
            return tasks;
        }

        Random random = rng == null ? new Random() : rng;
        Occupancy occupancy = new Occupancy(layout.bounds());

        reserveInfrastructure(occupancy, layout, settings);

        // Équipements de quartier placés en priorité, car ils donnent une
        // lecture fonctionnelle au bourg.
        findLot(layout, LotRole.CHURCH).ifPresent(church ->
                tryBuildCemetery(tasks, world, setBlock, occupancy, church, baseY));
        findLot(layout, LotRole.FARM).ifPresent(farm ->
                tryBuildOrchard(tasks, world, setBlock, occupancy, farm, baseY));
        findLot(layout, LotRole.FORGE).ifPresent(forge ->
                tryBuildForgeStock(tasks, world, setBlock, occupancy, forge, baseY));

        int budget = settings.decorationBudget();
        if (budget <= 0) {
            return tasks;
        }

        List<Cell> candidates = createCandidates(layout.bounds(), random);
        int built = 0;
        int index = 0;
        while (built < budget && index < candidates.size()) {
            Cell candidate = candidates.get(index++);
            int type = Math.floorMod(
                    candidate.x() * 31 + candidate.z() * 17 + random.nextInt(97),
                    8
            );
            boolean placed = switch (type) {
                case 0 -> tryBuildTree(tasks, world, setBlock, occupancy,
                        candidate.x(), candidate.z(), baseY, random);
                case 1 -> tryBuildWoodpile(tasks, world, setBlock, occupancy,
                        candidate.x(), candidate.z(), baseY, random);
                case 2 -> tryBuildHaystack(tasks, setBlock, occupancy,
                        candidate.x(), candidate.z(), baseY);
                case 3 -> tryBuildCart(tasks, world, setBlock, occupancy,
                        candidate.x(), candidate.z(), baseY, random);
                case 4 -> tryBuildLaundry(tasks, world, setBlock, occupancy,
                        candidate.x(), candidate.z(), baseY, random);
                case 5 -> tryBuildFlowerPatch(tasks, setBlock, occupancy,
                        candidate.x(), candidate.z(), baseY, random);
                case 6 -> tryBuildWaysideShrine(tasks, setBlock, occupancy,
                        candidate.x(), candidate.z(), baseY);
                default -> tryBuildNoticeBoard(tasks, world, setBlock, occupancy,
                        candidate.x(), candidate.z(), baseY, random);
            };
            if (placed) {
                built++;
            }
        }

        return tasks;
    }

    private static java.util.Optional<LotPlan> findLot(VillageLayoutPlan layout,
                                                        LotRole role) {
        return layout.lots().stream()
                .filter(lot -> lot.role() == role)
                .findFirst();
    }

    private static void reserveInfrastructure(Occupancy occupancy,
                                              VillageLayoutPlan layout,
                                              VillageLayoutSettings settings) {
        for (StreetPlan street : layout.streets()) {
            if (street.horizontal()) {
                occupancy.reserve(
                        street.minX() - 1,
                        street.maxX() + 1,
                        street.startZ() - street.halfWidth() - 2,
                        street.startZ() + street.halfWidth() + 2
                );
            } else {
                occupancy.reserve(
                        street.startX() - street.halfWidth() - 2,
                        street.startX() + street.halfWidth() + 2,
                        street.minZ() - 1,
                        street.maxZ() + 1
                );
            }
        }

        for (LotPlan lot : layout.lots()) {
            occupancy.reserve(
                    lot.siteMinX() - 1,
                    lot.siteMaxX() + 1,
                    lot.siteMinZ() - 1,
                    lot.siteMaxZ() + 1
            );

            // Le chemin entre la façade et la rue peut dépasser la réserve
            // rectangulaire du lot.
            reserveLine(
                    occupancy,
                    lot.frontageX(),
                    lot.frontageZ(),
                    lot.frontStepX(),
                    lot.frontStepZ(),
                    1
            );
        }

        var plaza = layout.anchors().get("plaza");
        int plazaX = plaza == null ? layout.bounds().centerX() : plaza.getBlockX();
        int plazaZ = plaza == null ? layout.bounds().centerZ() : plaza.getBlockZ();
        int half = settings.effectivePlazaSize() / 2 + 2;
        occupancy.reserve(
                plazaX - half,
                plazaX + half,
                plazaZ - half,
                plazaZ + half
        );
    }

    private static void reserveLine(Occupancy occupancy,
                                    int startX,
                                    int startZ,
                                    int endX,
                                    int endZ,
                                    int radius) {
        int x = startX;
        int z = startZ;
        while (x != endX || z != endZ) {
            occupancy.reserve(x - radius, x + radius, z - radius, z + radius);
            if (x != endX) {
                x += Integer.compare(endX, x);
            } else {
                z += Integer.compare(endZ, z);
            }
        }
        occupancy.reserve(endX - radius, endX + radius, endZ - radius, endZ + radius);
    }

    private static List<Cell> createCandidates(Bounds bounds, Random random) {
        List<Cell> candidates = new ArrayList<>();
        for (int x = bounds.minX() + 3; x <= bounds.maxX() - 3; x += 4) {
            for (int z = bounds.minZ() + 3; z <= bounds.maxZ() - 3; z += 4) {
                candidates.add(new Cell(
                        x + random.nextInt(3) - 1,
                        z + random.nextInt(3) - 1
                ));
            }
        }
        Collections.shuffle(candidates, random);
        return candidates;
    }

    private static void tryBuildCemetery(List<Runnable> tasks,
                                         World world,
                                         TerrainManager.SetBlock setBlock,
                                         Occupancy occupancy,
                                         LotPlan church,
                                         int baseY) {
        /*
         * L'église ferme volontairement la perspective nord du bourg. Poser le
         * cimetière uniquement derrière elle le rejetait donc hors des limites.
         * On privilégie ses bas-côtés, comme dans de nombreux bourgs anciens,
         * puis l'arrière lorsque la topologie laisse réellement de la place.
         */
        ClaimedArea area = claimAdjacentArea(
                occupancy,
                church,
                church.facing(),
                9,
                7,
                VillageStyle.leftOf(church.facing()),
                VillageStyle.rightOf(church.facing()),
                church.facing().getOppositeFace()
        );
        if (area == null) {
            return;
        }

        int centerX = area.centerX();
        int centerZ = area.centerZ();
        Rect rect = area.rect();
        for (int lateral = -4; lateral <= 4; lateral++) {
            for (int forward = -3; forward <= 3; forward++) {
                Cell point = localPoint(centerX, centerZ, church.facing(), lateral, forward);
                boolean edge = Math.abs(lateral) == 4 || Math.abs(forward) == 3;
                boolean gate = forward == 3 && Math.abs(lateral) <= 1;
                place(tasks, setBlock, point.x(), baseY, point.z(),
                        Math.floorMod(lateral + forward, 4) == 0
                                ? Material.MOSS_BLOCK
                                : Material.GRASS_BLOCK);
                if (edge && !gate) {
                    place(tasks, setBlock, point.x(), baseY + 1, point.z(),
                            Material.COBBLESTONE_WALL);
                }
            }
        }

        // Trois rangées irrégulières, avec une allée centrale.
        for (int lateral : new int[]{-3, 0, 3}) {
            for (int forward : new int[]{-2, 0, 2}) {
                if (lateral == 0 && forward == 2) {
                    continue;
                }
                Cell grave = localPoint(centerX, centerZ, church.facing(), lateral, forward);
                Cell head = new Cell(
                        grave.x() - church.facing().getModX(),
                        grave.z() - church.facing().getModZ()
                );
                boolean slabGrave = Math.floorMod(lateral * 7 + forward, 3) == 0;
                place(tasks, setBlock, grave.x(), baseY + 1, grave.z(),
                        slabGrave ? Material.STONE_BRICK_STAIRS : Material.COARSE_DIRT);
                place(tasks, setBlock, head.x(), baseY + 1, head.z(),
                        Math.floorMod(lateral + forward, 2) == 0
                                ? Material.STONE_BRICK_WALL
                                : Material.MOSSY_COBBLESTONE_WALL);
                if (world != null && slabGrave) {
                    tasks.add(() -> VillageStyle.setStair(
                            world,
                            grave.x(),
                            baseY + 1,
                            grave.z(),
                            Material.STONE_BRICK_STAIRS,
                            church.facing().getOppositeFace(),
                            Stairs.Half.BOTTOM,
                            Stairs.Shape.STRAIGHT
                    ));
                }
            }
        }

        Cell gate = localPoint(centerX, centerZ, church.facing(), 0, 3);
        place(tasks, setBlock, gate.x(), baseY + 1, gate.z(), Material.IRON_BARS);
        Cell tree = localPoint(centerX, centerZ, church.facing(), 3, -2);
        buildSmallTree(tasks, setBlock, tree.x(), tree.z(), baseY, Material.OAK_LOG, Material.OAK_LEAVES);
    }

    private static void tryBuildOrchard(List<Runnable> tasks,
                                        World world,
                                        TerrainManager.SetBlock setBlock,
                                        Occupancy occupancy,
                                        LotPlan farm,
                                        int baseY) {
        BlockFace side = VillageStyle.rightOf(farm.facing());
        int offset = sideRadius(farm) + farm.yardDepth() + 8;
        int centerX = farm.centerX() + side.getModX() * offset;
        int centerZ = farm.centerZ() + side.getModZ() * offset;
        Rect rect = new Rect(centerX - 5, centerX + 5, centerZ - 4, centerZ + 4);
        if (!occupancy.claim(rect, 1)) {
            // Le côté opposé est utilisé lorsqu'un lot voisin occupe le premier.
            centerX = farm.centerX() - side.getModX() * offset;
            centerZ = farm.centerZ() - side.getModZ() * offset;
            rect = new Rect(centerX - 5, centerX + 5, centerZ - 4, centerZ + 4);
            if (!occupancy.claim(rect, 1)) {
                return;
            }
        }

        for (int x = rect.minX(); x <= rect.maxX(); x++) {
            for (int z = rect.minZ(); z <= rect.maxZ(); z++) {
                Material ground = Math.floorMod(x * 13 + z * 19, 5) == 0
                        ? Material.COARSE_DIRT
                        : Material.GRASS_BLOCK;
                place(tasks, setBlock, x, baseY, z, ground);
            }
        }

        for (int dx : new int[]{-3, 0, 3}) {
            for (int dz : new int[]{-2, 2}) {
                Material leaves = Math.floorMod(dx + dz, 2) == 0
                        ? Material.FLOWERING_AZALEA_LEAVES
                        : Material.OAK_LEAVES;
                buildSmallTree(tasks, setBlock,
                        centerX + dx, centerZ + dz, baseY,
                        Material.OAK_LOG, leaves);
            }
        }
        int beeX = centerX + 4;
        int beeZ = centerZ;
        place(tasks, setBlock, beeX, baseY + 1, beeZ, Material.BEE_NEST);
        if (world != null) {
            tasks.add(() -> VillageStyle.setDirectional(
                    world,
                    beeX,
                    baseY + 1,
                    beeZ,
                    Material.BEE_NEST,
                    farm.facing()
            ));
        }
    }

    private static void tryBuildForgeStock(List<Runnable> tasks,
                                           World world,
                                           TerrainManager.SetBlock setBlock,
                                           Occupancy occupancy,
                                           LotPlan forge,
                                           int baseY) {
        ClaimedArea area = claimAdjacentArea(
                occupancy,
                forge,
                forge.facing(),
                7,
                7,
                VillageStyle.leftOf(forge.facing()),
                VillageStyle.rightOf(forge.facing()),
                forge.facing().getOppositeFace()
        );
        if (area == null) {
            /*
             * Le quartier artisanal peut être encadré par deux maisons. Dans
             * ce cas, on conserve l'identité fonctionnelle de la forge avec
             * une réserve compacte dans sa propre cour, sans forcer une
             * collision avec les voisins ou la muraille.
             */
            buildCompactForgeStock(tasks, world, setBlock, forge, baseY);
            return;
        }

        int centerX = area.centerX();
        int centerZ = area.centerZ();
        Rect rect = area.rect();

        for (int x = rect.minX(); x <= rect.maxX(); x++) {
            for (int z = rect.minZ(); z <= rect.maxZ(); z++) {
                place(tasks, setBlock, x, baseY, z,
                        Math.floorMod(x + z, 2) == 0
                                ? Material.GRAVEL
                                : Material.COARSE_DIRT);
            }
        }

        // Charbon, minerai et bois de chauffe sont regroupés sous un petit
        // appentis, ce qui renforce la fonction de la forge à distance.
        for (int dy = 1; dy <= 3; dy++) {
            place(tasks, setBlock, rect.minX(), baseY + dy, rect.minZ(), Material.STRIPPED_SPRUCE_LOG);
            place(tasks, setBlock, rect.maxX(), baseY + dy, rect.minZ(), Material.STRIPPED_SPRUCE_LOG);
            place(tasks, setBlock, rect.minX(), baseY + dy, rect.maxZ(), Material.STRIPPED_SPRUCE_LOG);
            place(tasks, setBlock, rect.maxX(), baseY + dy, rect.maxZ(), Material.STRIPPED_SPRUCE_LOG);
        }
        for (int x = rect.minX() - 1; x <= rect.maxX() + 1; x++) {
            for (int z = rect.minZ() - 1; z <= rect.maxZ() + 1; z++) {
                slab(tasks, world, setBlock, x, baseY + 4, z,
                        Material.DARK_OAK_SLAB, Slab.Type.TOP);
            }
        }
        place(tasks, setBlock, centerX - 1, baseY + 1, centerZ, Material.COAL_BLOCK);
        place(tasks, setBlock, centerX, baseY + 1, centerZ, Material.RAW_IRON_BLOCK);
        place(tasks, setBlock, centerX + 1, baseY + 1, centerZ, Material.BARREL);
        place(tasks, setBlock, centerX - 2, baseY + 1, centerZ + 2, Material.SPRUCE_LOG);
        place(tasks, setBlock, centerX - 1, baseY + 1, centerZ + 2, Material.SPRUCE_LOG);
        place(tasks, setBlock, centerX, baseY + 1, centerZ + 2, Material.SPRUCE_LOG);
    }

    private static boolean tryBuildTree(List<Runnable> tasks,
                                        World world,
                                        TerrainManager.SetBlock setBlock,
                                        Occupancy occupancy,
                                        int x,
                                        int z,
                                        int baseY,
                                        Random random) {
        Rect rect = new Rect(x - 2, x + 2, z - 2, z + 2);
        if (!occupancy.claim(rect, 1)) {
            return false;
        }
        Material log = random.nextBoolean() ? Material.OAK_LOG : Material.BIRCH_LOG;
        Material leaves = log == Material.BIRCH_LOG ? Material.BIRCH_LEAVES : Material.OAK_LEAVES;
        buildSmallTree(tasks, setBlock, x, z, baseY, log, leaves);
        if (random.nextInt(4) == 0) {
            place(tasks, setBlock, x + 1, baseY + 1, z, Material.BEE_NEST);
            if (world != null) {
                tasks.add(() -> VillageStyle.setDirectional(
                        world, x + 1, baseY + 1, z,
                        Material.BEE_NEST, BlockFace.EAST));
            }
        }
        return true;
    }

    private static boolean tryBuildWoodpile(List<Runnable> tasks,
                                            World world,
                                            TerrainManager.SetBlock setBlock,
                                            Occupancy occupancy,
                                            int x,
                                            int z,
                                            int baseY,
                                            Random random) {
        boolean alongX = random.nextBoolean();
        Rect rect = alongX
                ? new Rect(x - 2, x + 2, z - 1, z + 1)
                : new Rect(x - 1, x + 1, z - 2, z + 2);
        if (!occupancy.claim(rect, 1)) {
            return false;
        }

        for (int row = -1; row <= 1; row++) {
            for (int length = -2; length <= 2; length++) {
                int px = alongX ? x + length : x + row;
                int pz = alongX ? z + row : z + length;
                int y = baseY + 1 + Math.abs(row);
                place(tasks, setBlock, px, y, pz, Material.SPRUCE_LOG);
                if (world != null) {
                    Axis axis = alongX ? Axis.X : Axis.Z;
                    tasks.add(() -> VillageStyle.setLogAxis(
                            world, px, y, pz, Material.SPRUCE_LOG, axis));
                }
            }
        }
        place(tasks, setBlock, x, baseY, z, Material.COARSE_DIRT);
        return true;
    }

    private static boolean tryBuildHaystack(List<Runnable> tasks,
                                            TerrainManager.SetBlock setBlock,
                                            Occupancy occupancy,
                                            int x,
                                            int z,
                                            int baseY) {
        Rect rect = new Rect(x - 1, x + 1, z - 1, z + 1);
        if (!occupancy.claim(rect, 1)) {
            return false;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (Math.abs(dx) + Math.abs(dz) <= 1) {
                    place(tasks, setBlock, x + dx, baseY + 1, z + dz, Material.HAY_BLOCK);
                }
            }
        }
        place(tasks, setBlock, x, baseY + 2, z, Material.HAY_BLOCK);
        place(tasks, setBlock, x + 1, baseY + 1, z + 1, Material.BARREL);
        return true;
    }

    private static boolean tryBuildCart(List<Runnable> tasks,
                                        World world,
                                        TerrainManager.SetBlock setBlock,
                                        Occupancy occupancy,
                                        int x,
                                        int z,
                                        int baseY,
                                        Random random) {
        boolean alongX = random.nextBoolean();
        Rect rect = alongX
                ? new Rect(x - 3, x + 3, z - 1, z + 1)
                : new Rect(x - 1, x + 1, z - 3, z + 3);
        if (!occupancy.claim(rect, 1)) {
            return false;
        }

        for (int longitudinal = -1; longitudinal <= 1; longitudinal++) {
            for (int lateral = -1; lateral <= 1; lateral++) {
                int px = alongX ? x + longitudinal : x + lateral;
                int pz = alongX ? z + lateral : z + longitudinal;
                place(tasks, setBlock, px, baseY + 1, pz, Material.SPRUCE_SLAB);
                if (world != null) {
                    tasks.add(() -> VillageStyle.setSlab(
                            world, px, baseY + 1, pz,
                            Material.SPRUCE_SLAB, Slab.Type.TOP));
                }
            }
        }

        // Roues sous forme de trappes verticales.
        for (int sign : new int[]{-1, 1}) {
            int wheelX = alongX ? x : x + sign * 2;
            int wheelZ = alongX ? z + sign * 2 : z;
            place(tasks, setBlock, wheelX, baseY + 1, wheelZ, Material.DARK_OAK_TRAPDOOR);
            if (world != null) {
                BlockFace facing = alongX
                        ? (sign < 0 ? BlockFace.NORTH : BlockFace.SOUTH)
                        : (sign < 0 ? BlockFace.WEST : BlockFace.EAST);
                tasks.add(() -> VillageStyle.setTrapdoor(
                        world,
                        wheelX,
                        baseY + 1,
                        wheelZ,
                        Material.DARK_OAK_TRAPDOOR,
                        facing,
                        true,
                        Bisected.Half.BOTTOM
                ));
            }
        }

        for (int length = 2; length <= 3; length++) {
            int px = alongX ? x + length : x;
            int pz = alongX ? z : z + length;
            place(tasks, setBlock, px, baseY + 1, pz, Material.OAK_FENCE);
        }
        place(tasks, setBlock, x, baseY + 2, z,
                random.nextBoolean() ? Material.BARREL : Material.HAY_BLOCK);
        return true;
    }

    private static boolean tryBuildLaundry(List<Runnable> tasks,
                                           World world,
                                           TerrainManager.SetBlock setBlock,
                                           Occupancy occupancy,
                                           int x,
                                           int z,
                                           int baseY,
                                           Random random) {
        boolean alongX = random.nextBoolean();
        Rect rect = alongX
                ? new Rect(x - 3, x + 3, z - 1, z + 1)
                : new Rect(x - 1, x + 1, z - 3, z + 3);
        if (!occupancy.claim(rect, 1)) {
            return false;
        }

        for (int sign : new int[]{-1, 1}) {
            int postX = alongX ? x + sign * 3 : x;
            int postZ = alongX ? z : z + sign * 3;
            for (int dy = 1; dy <= 3; dy++) {
                place(tasks, setBlock, postX, baseY + dy, postZ, Material.OAK_FENCE);
            }
        }
        for (int position = -2; position <= 2; position++) {
            int clothX = alongX ? x + position : x;
            int clothZ = alongX ? z : z + position;
            Material cloth = switch (Math.floorMod(position, 3)) {
                case 0 -> Material.WHITE_WOOL;
                case 1 -> Material.LIGHT_BLUE_WOOL;
                default -> Material.YELLOW_WOOL;
            };
            place(tasks, setBlock, clothX, baseY + 2, clothZ, cloth);
        }
        place(tasks, setBlock, x + (alongX ? 0 : 1), baseY + 1,
                z + (alongX ? 1 : 0), Material.WATER_CAULDRON);
        return true;
    }

    private static boolean tryBuildFlowerPatch(List<Runnable> tasks,
                                               TerrainManager.SetBlock setBlock,
                                               Occupancy occupancy,
                                               int x,
                                               int z,
                                               int baseY,
                                               Random random) {
        Rect rect = new Rect(x - 1, x + 1, z - 1, z + 1);
        if (!occupancy.claim(rect, 0)) {
            return false;
        }
        Material[] flowers = {
                Material.POPPY,
                Material.CORNFLOWER,
                Material.OXEYE_DAISY,
                Material.ALLIUM,
                Material.AZURE_BLUET
        };
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                place(tasks, setBlock, x + dx, baseY, z + dz,
                        Math.floorMod(dx + dz, 2) == 0
                                ? Material.MOSS_BLOCK
                                : Material.GRASS_BLOCK);
                if (random.nextInt(4) != 0) {
                    place(tasks, setBlock, x + dx, baseY + 1, z + dz,
                            flowers[random.nextInt(flowers.length)]);
                }
            }
        }
        return true;
    }

    private static boolean tryBuildWaysideShrine(List<Runnable> tasks,
                                                 TerrainManager.SetBlock setBlock,
                                                 Occupancy occupancy,
                                                 int x,
                                                 int z,
                                                 int baseY) {
        Rect rect = new Rect(x - 1, x + 1, z - 1, z + 1);
        if (!occupancy.claim(rect, 1)) {
            return false;
        }
        place(tasks, setBlock, x, baseY, z, Material.MOSSY_STONE_BRICKS);
        place(tasks, setBlock, x, baseY + 1, z, Material.STONE_BRICK_WALL);
        place(tasks, setBlock, x, baseY + 2, z, Material.CHISELED_STONE_BRICKS);
        place(tasks, setBlock, x, baseY + 3, z, Material.LANTERN);
        place(tasks, setBlock, x - 1, baseY + 1, z, Material.POTTED_POPPY);
        place(tasks, setBlock, x + 1, baseY + 1, z, Material.POTTED_AZURE_BLUET);
        return true;
    }

    private static boolean tryBuildNoticeBoard(List<Runnable> tasks,
                                               World world,
                                               TerrainManager.SetBlock setBlock,
                                               Occupancy occupancy,
                                               int x,
                                               int z,
                                               int baseY,
                                               Random random) {
        boolean alongX = random.nextBoolean();
        Rect rect = alongX
                ? new Rect(x - 2, x + 2, z - 1, z + 1)
                : new Rect(x - 1, x + 1, z - 2, z + 2);
        if (!occupancy.claim(rect, 1)) {
            return false;
        }

        for (int sign : new int[]{-1, 1}) {
            int postX = alongX ? x + sign * 2 : x;
            int postZ = alongX ? z : z + sign * 2;
            place(tasks, setBlock, postX, baseY + 1, postZ, Material.STRIPPED_SPRUCE_LOG);
            place(tasks, setBlock, postX, baseY + 2, postZ, Material.STRIPPED_SPRUCE_LOG);
        }
        for (int offset = -1; offset <= 1; offset++) {
            int boardX = alongX ? x + offset : x;
            int boardZ = alongX ? z : z + offset;
            place(tasks, setBlock, boardX, baseY + 2, boardZ, Material.SPRUCE_PLANKS);
        }
        place(tasks, setBlock, x, baseY + 2, z, Material.OAK_WALL_SIGN);
        if (world != null) {
            tasks.add(() -> VillageStyle.setDirectional(
                    world, x, baseY + 2, z,
                    Material.OAK_WALL_SIGN,
                    alongX ? BlockFace.SOUTH : BlockFace.EAST));
        }
        slab(tasks, world, setBlock, x, baseY + 3, z,
                Material.SPRUCE_SLAB, Slab.Type.TOP);
        return true;
    }

    private static void buildSmallTree(List<Runnable> tasks,
                                       TerrainManager.SetBlock setBlock,
                                       int x,
                                       int z,
                                       int baseY,
                                       Material log,
                                       Material leaves) {
        place(tasks, setBlock, x, baseY, z, Material.ROOTED_DIRT);
        for (int dy = 1; dy <= 4; dy++) {
            place(tasks, setBlock, x, baseY + dy, z, log);
        }
        for (int dy = 3; dy <= 5; dy++) {
            int radius = dy == 4 ? 2 : 1;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) <= radius + 1) {
                        place(tasks, setBlock, x + dx, baseY + dy, z + dz, leaves);
                    }
                }
            }
        }
        place(tasks, setBlock, x, baseY + 6, z, leaves);
    }

    /**
     * Variante compacte utilisée lorsque les parcelles voisines ne laissent
     * pas assez de place pour l'appentis détaché. Les piles restent dans les
     * angles de la cour et préservent l'axe porte-rue au centre.
     */
    private static void buildCompactForgeStock(List<Runnable> tasks,
                                               World world,
                                               TerrainManager.SetBlock setBlock,
                                               LotPlan forge,
                                               int baseY) {
        int front = frontRadius(forge);
        Cell ore = localPoint(
                forge.centerX(), forge.centerZ(), forge.facing(), -2, front + 2);
        Cell coal = localPoint(
                forge.centerX(), forge.centerZ(), forge.facing(), 2, front + 2);
        Cell firstLog = localPoint(
                forge.centerX(), forge.centerZ(), forge.facing(), -2, front + 3);
        Cell secondLog = localPoint(
                forge.centerX(), forge.centerZ(), forge.facing(), -1, front + 3);

        place(tasks, setBlock, ore.x(), baseY + 1, ore.z(), Material.RAW_IRON_BLOCK);
        place(tasks, setBlock, coal.x(), baseY + 1, coal.z(), Material.COAL_BLOCK);
        place(tasks, setBlock, firstLog.x(), baseY + 1, firstLog.z(), Material.SPRUCE_LOG);
        place(tasks, setBlock, secondLog.x(), baseY + 1, secondLog.z(), Material.SPRUCE_LOG);

        if (world != null) {
            BlockFace lateral = VillageStyle.rightOf(forge.facing());
            Axis axis = lateral == BlockFace.EAST || lateral == BlockFace.WEST
                    ? Axis.X
                    : Axis.Z;
            tasks.add(() -> VillageStyle.setLogAxis(
                    world, firstLog.x(), baseY + 1, firstLog.z(),
                    Material.SPRUCE_LOG, axis));
            tasks.add(() -> VillageStyle.setLogAxis(
                    world, secondLog.x(), baseY + 1, secondLog.z(),
                    Material.SPRUCE_LOG, axis));
        }
    }

    private static Rect orientedRect(int centerX,
                                     int centerZ,
                                     BlockFace front,
                                     int width,
                                     int depth) {
        boolean northSouth = front == BlockFace.NORTH || front == BlockFace.SOUTH;
        int halfX = (northSouth ? width : depth) / 2;
        int halfZ = (northSouth ? depth : width) / 2;
        return new Rect(
                centerX - halfX,
                centerX + halfX,
                centerZ - halfZ,
                centerZ + halfZ
        );
    }

    /**
     * Réserve une emprise au contact d'un lot sans dépendre d'une distance
     * approximative calculée depuis son seul bâtiment. Les limites de site
     * incluent les ailes et les jardins : cette méthode reste donc correcte
     * pour toutes les orientations et toutes les variantes architecturales.
     *
     * @return l'emprise réservée, ou {@code null} lorsqu'aucun côté proposé
     *         n'est disponible.
     */
    private static ClaimedArea claimAdjacentArea(Occupancy occupancy,
                                                 LotPlan lot,
                                                 BlockFace orientation,
                                                 int width,
                                                 int depth,
                                                 BlockFace... preferredSides) {
        Rect origin = orientedRect(0, 0, orientation, width, depth);

        for (BlockFace side : preferredSides) {
            if (side == null) {
                continue;
            }

            int centerX = lot.centerX();
            int centerZ = lot.centerZ();

            switch (side) {
                case EAST -> centerX = lot.siteMaxX() + 3 - origin.minX();
                case WEST -> centerX = lot.siteMinX() - 3 - origin.maxX();
                case SOUTH -> centerZ = lot.siteMaxZ() + 3 - origin.minZ();
                case NORTH -> centerZ = lot.siteMinZ() - 3 - origin.maxZ();
                default -> {
                    continue;
                }
            }

            Rect rect = orientedRect(centerX, centerZ, orientation, width, depth);
            if (occupancy.claim(rect, 1)) {
                return new ClaimedArea(centerX, centerZ, rect);
            }
        }

        return null;
    }

    private static Cell localPoint(int centerX,
                                   int centerZ,
                                   BlockFace front,
                                   int lateral,
                                   int forward) {
        BlockFace right = VillageStyle.rightOf(front);
        return new Cell(
                centerX + right.getModX() * lateral + front.getModX() * forward,
                centerZ + right.getModZ() * lateral + front.getModZ() * forward
        );
    }

    private static int frontRadius(LotPlan lot) {
        return (lot.facing() == BlockFace.NORTH || lot.facing() == BlockFace.SOUTH)
                ? (lot.footprintDepth() - 1) / 2
                : (lot.footprintWidth() - 1) / 2;
    }

    private static int sideRadius(LotPlan lot) {
        return (lot.facing() == BlockFace.NORTH || lot.facing() == BlockFace.SOUTH)
                ? (lot.footprintWidth() - 1) / 2
                : (lot.footprintDepth() - 1) / 2;
    }

    private static void slab(List<Runnable> tasks,
                             World world,
                             TerrainManager.SetBlock setBlock,
                             int x,
                             int y,
                             int z,
                             Material material,
                             Slab.Type type) {
        place(tasks, setBlock, x, y, z, material);
        if (world != null) {
            tasks.add(() -> VillageStyle.setSlab(
                    world, x, y, z, material, type));
        }
    }

    private static void place(List<Runnable> tasks,
                              TerrainManager.SetBlock setBlock,
                              int x,
                              int y,
                              int z,
                              Material material) {
        tasks.add(() -> setBlock.set(x, y, z, material));
    }

    private record Cell(int x, int z) {}

    private record ClaimedArea(int centerX, int centerZ, Rect rect) {}

    private record Rect(int minX, int maxX, int minZ, int maxZ) {
        Rect {
            if (minX > maxX || minZ > maxZ) {
                throw new IllegalArgumentException("Rectangle décoratif invalide.");
            }
        }
    }

    /**
     * Carte compacte des cellules déjà attribuées à une fonction.
     */
    private static final class Occupancy {
        private final Bounds bounds;
        private final Set<Long> cells = new HashSet<>();

        private Occupancy(Bounds bounds) {
            this.bounds = bounds;
        }

        private boolean claim(Rect rect, int margin) {
            if (!inside(rect, margin) || !isFree(rect, margin)) {
                return false;
            }
            reserve(
                    rect.minX() - margin,
                    rect.maxX() + margin,
                    rect.minZ() - margin,
                    rect.maxZ() + margin
            );
            return true;
        }

        private boolean inside(Rect rect, int margin) {
            return rect.minX() - margin >= bounds.minX() + 1
                    && rect.maxX() + margin <= bounds.maxX() - 1
                    && rect.minZ() - margin >= bounds.minZ() + 1
                    && rect.maxZ() + margin <= bounds.maxZ() - 1;
        }

        private boolean isFree(Rect rect, int margin) {
            for (int x = rect.minX() - margin; x <= rect.maxX() + margin; x++) {
                for (int z = rect.minZ() - margin; z <= rect.maxZ() + margin; z++) {
                    if (cells.contains(key(x, z))) {
                        return false;
                    }
                }
            }
            return true;
        }

        private void reserve(int minX, int maxX, int minZ, int maxZ) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cells.add(key(x, z));
                }
            }
        }

        private static long key(int x, int z) {
            return ((long) x << 32) ^ (z & 0xffffffffL);
        }
    }
}

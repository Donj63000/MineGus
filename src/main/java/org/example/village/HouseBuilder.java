package org.example.village;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.example.village.VillageLayoutPlan.HouseArchetype;
import static org.example.village.VillageLayoutPlan.HouseSpec;
import static org.example.village.VillageLayoutPlan.LotPlan;
import static org.example.village.VillageLayoutPlan.RoofStyle;

/**
 * G√©n√©rateur principal des maisons et des petits lots annexes.
 *
 * Le but de cette version est d'obtenir un rendu plus "village de joueur" :
 * fa√ßades plus √©paisses, toits lisibles, porches, petits jardins, int√©rieurs
 * cr√©dibles et d√©tails de terrain autour des b√¢timents.
 */
public final class HouseBuilder {

    private HouseBuilder() {}

    public static List<Runnable> buildHouse(World world, LotPlan lot, int baseY, TerrainManager.SetBlock sb, Random rng) {
        List<Runnable> tasks = new ArrayList<>();
        HouseSpec spec = lot.houseSpec();
        if (spec == null) {
            return tasks;
        }

        VillageStyle.Palette palette = VillageStyle.medievalPalette(spec.accentMaterial());

        /*
         * Deux niveaux habitables demandent sept blocs de fa√ßade : quatre pour
         * le rez-de-chauss√©e, deux pour la baie haute et un bandeau sous toit.
         * Plusieurs variantes historiques ne d√©claraient que cinq ou six
         * blocs ; leurs fen√™tres sup√©rieures √©taient alors coup√©es par le toit.
         */
        int effectiveWallHeight = spec.twoStory()
                ? Math.max(7, spec.wallHeight())
                : Math.max(4, spec.wallHeight());

        HouseVolume main = new HouseVolume(
                lot.buildX(),
                lot.buildZ(),
                lot.footprintWidth(),
                lot.footprintDepth(),
                effectiveWallHeight,
                spec.roofStyle()
        );

        HouseVolume annex = annexFor(main, lot);

        // 1) Base du terrain / soubassement.
        buildFoundationSkirt(tasks, sb, main, baseY, palette, Math.min(2, Math.max(1, spec.foundationStep() + 1)));
        if (annex != null) {
            buildFoundationSkirt(tasks, sb, annex, baseY, palette, 1);
        }

        // 2) Volumes principaux.
        buildVolume(tasks, world, sb, main, baseY, lot.facing(), palette, true, spec, 0);
        if (annex != null) {
            buildVolume(tasks, world, sb, annex, baseY, lot.facing(), palette, false, spec, 1);
            stitchVolumes(tasks, sb, main, annex, baseY, palette);
        }

        // 3) Fa√ßade et entr√©e.
        buildDoor(tasks, world, sb, lot, baseY, palette);
        buildFacade(tasks, world, sb, lot, main, baseY, palette);
        buildPorch(tasks, world, sb, lot, baseY, palette);

        // 4) Int√©rieur et petits accents ext√©rieurs.
        buildInterior(tasks, world, sb, lot, main, annex, baseY, palette);
        buildArchetypeAccent(tasks, world, sb, lot, main, baseY, palette);
        buildYard(tasks, world, sb, lot, main, baseY, palette);
        buildChimney(tasks, sb, lot, main, baseY, palette);

        // 5) Niveau suppl√©mentaire / lucarne si la maison le demande.
        if (spec.twoStory()) {
            buildSecondFloor(tasks, world, sb, lot, main, baseY, palette);
        }
        if (spec.hasDormer()) {
            buildDormer(tasks, world, sb, lot, main, baseY, palette);
        }
        return tasks;
    }

    /**
     * Ferme orient√©e vers sa rue : portail, all√©e et outils restent du c√¥t√© de
     * la fa√ßade, quelle que soit l'orientation attribu√©e par le planificateur.
     */
    public static List<Runnable> buildFarm(World world,
                                           LotPlan lot,
                                           int surfaceY,
                                           List<Material> crops,
                                           TerrainManager.SetBlock sb,
                                           Random rng) {
        return buildFarmGrid(
                world,
                lot.centerX(),
                lot.centerZ(),
                lot.facing(),
                surfaceY,
                crops,
                sb,
                rng
        );
    }

    /**
     * Signature historique conserv√©e pour les autres int√©grations du plugin.
     */
    public static List<Runnable> buildFarm(Location base,
                                           List<Material> crops,
                                           TerrainManager.SetBlock sb,
                                           Random rng) {
        if (base == null) {
            return List.of();
        }
        return buildFarmGrid(
                base.getWorld(),
                base.getBlockX() + 5,
                base.getBlockZ() + 5,
                BlockFace.NORTH,
                base.getBlockY(),
                crops,
                sb,
                rng
        );
    }

    private static List<Runnable> buildFarmGrid(World world,
                                                int centerX,
                                                int centerZ,
                                                BlockFace front,
                                                int surfaceY,
                                                List<Material> crops,
                                                TerrainManager.SetBlock sb,
                                                Random rng) {
        List<Runnable> tasks = new ArrayList<>();
        Random random = rng != null ? rng : new Random();
        int half = 5;

        for (int lateral = -half; lateral <= half; lateral++) {
            for (int forward = -half; forward <= half; forward++) {
                Point point = localPoint(centerX, centerZ, front, lateral, forward);
                boolean edge = Math.abs(lateral) == half || Math.abs(forward) == half;
                boolean entrance = forward == half && Math.abs(lateral) <= 1;

                if (edge) {
                    place(tasks, sb, point.x(), surfaceY, point.z(),
                            Math.floorMod(lateral + forward, 3) == 0
                                    ? Material.COARSE_DIRT
                                    : Material.PACKED_MUD);
                    if (!entrance) {
                        place(tasks, sb, point.x(), surfaceY + 1, point.z(), Material.OAK_FENCE);
                    }
                    continue;
                }

                // Une all√©e transversale dessert chaque planche de culture.
                if (forward == half - 1) {
                    place(tasks, sb, point.x(), surfaceY, point.z(), Material.DIRT_PATH);
                    continue;
                }

                // Canal d√©centr√© pour que le portail d√©bouche sur une all√©e
                // praticable plut√¥t que directement dans l'eau.
                if (lateral == -1) {
                    place(tasks, sb, point.x(), surfaceY, point.z(), Material.WATER);
                    if (Math.floorMod(forward, 3) == 0) {
                        place(tasks, sb, point.x(), surfaceY + 1, point.z(), Material.LILY_PAD);
                    }
                    continue;
                }

                place(tasks, sb, point.x(), surfaceY, point.z(), Material.FARMLAND);
                Material crop = cropFor(crops, random, lateral, forward);
                place(tasks, sb, point.x(), surfaceY + 1, point.z(), crop);
                matureCrop(tasks, world, point.x(), surfaceY + 1, point.z(), crop);
            }
        }

        // Portillon et chemin d'acc√®s align√©s sur la rue.
        Point gatePoint = localPoint(centerX, centerZ, front, 0, half);
        place(tasks, sb, gatePoint.x(), surfaceY + 1, gatePoint.z(), Material.OAK_FENCE_GATE);
        gate(tasks, world, gatePoint.x(), surfaceY + 1, gatePoint.z(),
                Material.OAK_FENCE_GATE, front, false, true);
        for (int forward = half; forward <= half + 2; forward++) {
            for (int lateral = -1; lateral <= 1; lateral++) {
                Point point = localPoint(centerX, centerZ, front, lateral, forward);
                place(tasks, sb, point.x(), surfaceY, point.z(),
                        lateral == 0 ? Material.DIRT_PATH : Material.GRAVEL);
            }
        }

        // R√©serve d'outils dans l'angle arri√®re droit.
        placeFarmDetail(tasks, sb, centerX, surfaceY + 1, centerZ, front,
                3, -3, Material.COMPOSTER);
        placeFarmDetail(tasks, sb, centerX, surfaceY + 1, centerZ, front,
                4, -3, Material.BARREL);
        placeFarmDetail(tasks, sb, centerX, surfaceY + 1, centerZ, front,
                3, -4, Material.HAY_BLOCK);
        placeFarmDetail(tasks, sb, centerX, surfaceY + 1, centerZ, front,
                4, -4, Material.CRAFTING_TABLE);

        // √âpouvantail lisible depuis l'entr√©e.
        Point scarecrow = localPoint(centerX, centerZ, front, 2, -1);
        place(tasks, sb, scarecrow.x(), surfaceY + 1, scarecrow.z(), Material.OAK_FENCE);
        place(tasks, sb, scarecrow.x(), surfaceY + 2, scarecrow.z(), Material.OAK_FENCE);
        BlockFace right = VillageStyle.rightOf(front);
        place(tasks, sb,
                scarecrow.x() + right.getModX(),
                surfaceY + 2,
                scarecrow.z() + right.getModZ(),
                Material.OAK_FENCE);
        place(tasks, sb,
                scarecrow.x() - right.getModX(),
                surfaceY + 2,
                scarecrow.z() - right.getModZ(),
                Material.OAK_FENCE);
        place(tasks, sb, scarecrow.x(), surfaceY + 3, scarecrow.z(), Material.HAY_BLOCK);
        place(tasks, sb, scarecrow.x(), surfaceY + 4, scarecrow.z(), Material.CARVED_PUMPKIN);
        if (world != null) {
            tasks.add(() -> VillageStyle.setDirectional(
                    world,
                    scarecrow.x(),
                    surfaceY + 4,
                    scarecrow.z(),
                    Material.CARVED_PUMPKIN,
                    front
            ));
        }

        // Quatre lanternes basses marquent le p√©rim√®tre sans cr√©er de pyl√¥nes.
        for (int lateral : new int[]{-half, half}) {
            for (int forward : new int[]{-half, half}) {
                Point point = localPoint(centerX, centerZ, front, lateral, forward);
                place(tasks, sb, point.x(), surfaceY + 2, point.z(), Material.LANTERN);
            }
        }
        return tasks;
    }

    /**
     * Enclos orient√©, avec abri arri√®re et zone centrale r√©ellement libre pour
     * les animaux.
     */
    public static List<Runnable> buildPen(Plugin plugin,
                                          World world,
                                          LotPlan lot,
                                          int surfaceY,
                                          int villageId,
                                          TerrainManager.SetBlock sb) {
        return buildPenGrid(
                plugin,
                world,
                lot.centerX(),
                lot.centerZ(),
                lot.facing(),
                surfaceY,
                villageId,
                sb
        );
    }

    /**
     * Signature historique conserv√©e pour les appels existants.
     */
    public static List<Runnable> buildPen(Plugin plugin,
                                          Location base,
                                          int villageId,
                                          TerrainManager.SetBlock sb) {
        if (base == null) {
            return List.of();
        }
        return buildPenGrid(
                plugin,
                base.getWorld(),
                base.getBlockX() + 4,
                base.getBlockZ() + 5,
                BlockFace.NORTH,
                base.getBlockY(),
                villageId,
                sb
        );
    }

    private static List<Runnable> buildPenGrid(Plugin plugin,
                                               World world,
                                               int centerX,
                                               int centerZ,
                                               BlockFace front,
                                               int surfaceY,
                                               int villageId,
                                               TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        int min = -4;
        int max = 5;

        for (int lateral = min; lateral <= max; lateral++) {
            for (int forward = min; forward <= max; forward++) {
                Point point = localPoint(centerX, centerZ, front, lateral, forward);
                boolean edge = lateral == min || lateral == max
                        || forward == min || forward == max;
                boolean entrance = forward == max && Math.abs(lateral) <= 1;
                int selector = Math.floorMod(point.x() * 17 + point.z() * 31, 7);
                Material ground = selector == 0
                        ? Material.COARSE_DIRT
                        : selector <= 2 ? Material.PACKED_MUD : Material.GRASS_BLOCK;
                place(tasks, sb, point.x(), surfaceY, point.z(), ground);
                if (edge && !entrance) {
                    place(tasks, sb, point.x(), surfaceY + 1, point.z(), Material.OAK_FENCE);
                }
            }
        }

        Point gatePoint = localPoint(centerX, centerZ, front, 0, max);
        place(tasks, sb, gatePoint.x(), surfaceY + 1, gatePoint.z(), Material.OAK_FENCE_GATE);
        gate(tasks, world, gatePoint.x(), surfaceY + 1, gatePoint.z(),
                Material.OAK_FENCE_GATE, front, false, true);
        for (int forward = max; forward <= max + 2; forward++) {
            Point point = localPoint(centerX, centerZ, front, 0, forward);
            place(tasks, sb, point.x(), surfaceY, point.z(), Material.PACKED_MUD);
        }

        // Abri ouvert au fond √† droite : les quatre poteaux restent hors de la
        // zone de circulation centrale.
        int shedMinLateral = 1;
        int shedMaxLateral = 4;
        int shedMinForward = -3;
        int shedMaxForward = 0;
        for (int lateral = shedMinLateral; lateral <= shedMaxLateral; lateral++) {
            for (int forward = shedMinForward; forward <= shedMaxF◊ﬁtˆ⁄$z{-ÆÈ‹j◊ùÜ˜W6Ufˆ«V÷Rfˆ«V÷R¿–¢ñÁB&ˆˆeí¿–¢&∆ˆ6¥f6Rf6ñÊr¿–¢fñ∆∆vU7Gñ∆RÂ∆WGFR∆WGFRí∞–¢f˜"ÜñÁBÇ“fˆ«V÷RÊ÷ñÂÇÇí“≤Ç√“fˆ«V÷RÊ÷ÖÇÇí≤≤Ç≤≤í∞–¢6∆"áF6∑2¬v˜&∆B¬6"¬Ç¬&ˆˆeí“¬fˆ«V÷RÊ÷ñÂ¢Çí“¬∆WGFRÁ&ˆˆe6∆"Çí¬6∆"ÂGóRÂDıì∞–¢6∆"áF6∑2¬v˜&∆B¬6"¬Ç¬&ˆˆeí“¬fˆ«V÷RÊ÷Ö¢Çí≤¬∆WGFRÁ&ˆˆe6∆"Çí¬6∆"ÂGóRÂDıì∞–¢––¢f˜"ÜñÁB¢“fˆ«V÷RÊ÷ñÂ¢Çì≤¢√“fˆ«V÷RÊ÷Ö¢Çì≤¢≤≤í∞–¢6∆"áF6∑2¬v˜&∆B¬6"¬fˆ«V÷RÊ÷ñÂÇÇí“¬&ˆˆeí“¬¢¬∆WGFRÁ&ˆˆe6∆"Çí¬6∆"ÂGóRÂDıì∞–¢6∆"áF6∑2¬v˜&∆B¬6"¬fˆ«V÷RÊ÷ÖÇÇí≤¬&ˆˆeí“¬¢¬∆WGFRÁ&ˆˆe6∆"Çí¬6∆"ÂGóRÂDıì∞–¢––¢––†–¢&ófFR7FFñ2fˆñB7FóF6Öfˆ«V÷W2Ñ∆ó7C≈'VÊÊ&∆S‚F6∑2¿–¢FW'&ñ‰÷ÊvW"Â6WD&∆ˆ6≤6"¿–¢Ü˜W6Ufˆ«V÷R÷ñ‚¿–¢Ü˜W6Ufˆ«V÷RÊÊWÇ¿–¢ñÁB&6Uí¿–¢fñ∆∆vU7Gñ∆RÂ∆WGFR∆WGFRí∞–¢ñÁB˜fW&∆÷ñÂÇ“÷FÇÊ÷ÇÜ÷ñ‚Ê÷ñÂÇÇí¬ÊÊWÇÊ÷ñÂÇÇíì∞–¢ñÁB˜fW&∆÷ÖÇ“÷FÇÊ÷ñ‚Ü÷ñ‚Ê÷ÖÇÇí¬ÊÊWÇÊ÷ÖÇÇíì∞–¢ñÁB˜fW&∆÷ñÂ¢“÷FÇÊ÷ÇÜ÷ñ‚Ê÷ñÂ¢Çí¬ÊÊWÇÊ÷ñÂ¢Çíì∞–¢ñÁB˜fW&∆÷Ö¢“÷FÇÊ÷ñ‚Ü÷ñ‚Ê÷Ö¢Çí¬ÊÊWÇÊ÷Ö¢Çíì∞–¢ñbÜ˜fW&∆÷ñÂÇ‚˜fW&∆÷ÖÇ«¬˜fW&∆÷ñÂ¢‚˜fW&∆÷Ö¢í∞–¢&WGW&„∞–¢––†–¢f˜"ÜñÁBÇ“˜fW&∆÷ñÂÉ≤Ç√“˜fW&∆÷ÖÉ≤Ç≤≤í∞–¢f˜"ÜñÁB¢“˜fW&∆÷ñÂ£≤¢√“˜fW&∆÷Ö£≤¢≤≤í∞–¢∆6RáF6∑2¬6"¬Ç¬&6Uí¬¢¬∆WGFRÊf∆ˆ˜"Çíì∞–¢––¢––†–¢ÚÚ˜Wg&RVÊRg&ñR6ˆ÷◊VÊñ6Fñˆ‚FÁ2∆R◊W"6ˆ÷◊V‚‚W&fÁB∆W0–¢ÚÚFWWÇfˆ«V÷W26R7WW'˜6ñVÁB6Á276vRWBf˜&÷ñVÁBVÊR÷76RFP–¢ÚÚ◊W'2˜FˆóG2R6VÁG&RFR∆÷ó6ˆ‚‡–¢ñbÜ˜fW&∆÷ñÂÇ”“˜fW&∆÷ÖÇí∞–¢ñÁB6VÁFW%¢“Ü˜fW&∆÷ñÂ¢≤˜fW&∆÷Ö¢íÚ#∞–¢f˜"ÜñÁB¢“÷FÇÊ÷ÇÜ˜fW&∆÷ñÂ¢≤¬6VÁFW%¢“ì∞–¢¢√“÷FÇÊ÷ñ‚Ü˜fW&∆÷Ö¢“¬6VÁFW%¢≤ì∞–¢¢≤≤í∞–¢f˜"ÜñÁBí“&6Uí≤≤í√“&6Uí≤3≤í≤≤í∞–¢∆6RáF6∑2¬6"¬˜fW&∆÷ñÂÇ¬í¬¢¬÷FW&ñ¬‰ï"ì∞–¢––¢––¢“V«6RñbÜ˜fW&∆÷ñÂ¢”“˜fW&∆÷Ö¢í∞–¢ñÁB6VÁFW%Ç“Ü˜fW&∆÷ñÂÇ≤˜fW&∆÷ÖÇíÚ#∞–¢f˜"ÜñÁBÇ“÷FÇÊ÷ÇÜ˜fW&∆÷ñÂÇ≤¬6VÁFW%Ç“ì∞–¢Ç√“÷FÇÊ÷ñ‚Ü˜fW&∆÷ÖÇ“¬6VÁFW%Ç≤ì∞–¢Ç≤≤í∞–¢f˜"ÜñÁBí“&6Uí≤≤í√“&6Uí≤3≤í≤≤í∞–¢∆6RáF6∑2¬6"¬Ç¬í¬˜fW&∆÷ñÂ¢¬÷FW&ñ¬‰ï"ì∞–¢––¢––¢––¢––†–¢&ófFR7FFñ2&ˆˆ∆V‚g&÷UGFW&‚ÜñÁBÇ¿–¢ñÁB¢¿–¢ñÁBí¿–¢ñÁB&6Uí¿–¢Ü˜W6Ufˆ«V÷Rfˆ«V÷R¿–¢ñÁBfˆ«V÷TñÊFWÇí∞–¢ñÁB∆ˆ6≈Ç“Ç“fˆ«V÷RÊ÷ñÂÇÇì∞–¢ñÁB∆ˆ6≈¢“¢“fˆ«V÷RÊ÷ñÂ¢Çì∞–¢ñÁB&V∆FófUí“í“&6Uì∞–¢&ˆˆ∆V‚Ê˜'FÖ6˜WFÖv∆¬“¢”“fˆ«V÷RÊ÷ñÂ¢Çê–¢«¬¢”“fˆ«V÷RÊ÷Ö¢Çì∞–¢&ˆˆ∆V‚V7EvW7Ev∆¬“Ç”“fˆ«V÷RÊ÷ñÂÇÇê–¢«¬Ç”“fˆ«V÷RÊ÷ÖÇÇì∞–†–¢Ú†–¢¢∆R&ÊFVRB|:óFvRL:óVÊBFR∆ÜWFWW"∆ˆ6∆RFR∆÷ó6ˆ‚¬WBÊˆ‡–¢¢FR¬v«FóGVFR'6ˆ«VRGR÷ˆÊFR‚∆W2÷ˆÁFÁG2&W7FVÁB,:ñwV∆ñW'2≤∆W0–¢¢fVÏ:ßG&W2¬6∆7VÃ:ñW2fÁB6WGFR‹:óFÜˆFR¬6ˆÁ6W'fVÁBF˜V¶˜W'2∆WW –¢¢&ñR6ˆ◊Ã:áFR‡–¢¢–¢&ˆˆ∆V‚f∆ˆ˜%FñR“&V∆FófUí”“Bbbfˆ«V÷RÁv∆ƒÜVñváBÇí„“c∞–¢&ˆˆ∆V‚Ê˜'FÖ6˜WFÖ˜7B“Ê˜'FÖ6˜WFÖv∆¿–¢bb∆ˆ6≈Ç‚ –¢bb∆ˆ6≈Ç¬fˆ«V÷RÊfˆ˜G&ñÁEvñGFÇÇí“–¢bb÷FÇÊf∆ˆ˜$÷ˆBÜ∆ˆ6≈Ç≤fˆ«V÷TñÊFWÇ¬2í”“∞–¢&ˆˆ∆V‚V7EvW7E˜7B“V7EvW7Ev∆¿–¢bb∆ˆ6≈¢‚ –¢bb∆ˆ6≈¢¬fˆ«V÷RÊfˆ˜G&ñÁDFWFÇÇí“–¢bb÷FÇÊf∆ˆ˜$÷ˆBÜ∆ˆ6≈¢≤fˆ«V÷TñÊFWÇ¬2í”“∞–†–¢&WGW&‚f∆ˆ˜%FñR«¬Ê˜'FÖ6˜WFÖ˜7B«¬V7EvW7E˜7C∞–¢––†–¢&ófFR7FFñ2&ˆˆ∆V‚W&ñ÷WFW"ÜñÁBÇ¬ñÁB¢¬Ü˜W6Ufˆ«V÷Rfˆ«V÷Rí∞–¢&WGW&‚Ç”“fˆ«V÷RÊ÷ñÂÇÇí«¬Ç”“fˆ«V÷RÊ÷ÖÇÇí«¬¢”“fˆ«V÷RÊ÷ñÂ¢Çí«¬¢”“fˆ«V÷RÊ÷Ö¢Çì∞–¢––†–¢&ófFR7FFñ2&ˆˆ∆V‚6˜&ÊW"ÜñÁBÇ¬ñÁB¢¬Ü˜W6Ufˆ«V÷Rfˆ«V÷Rí∞–¢&WGW&‚áÇ”“fˆ«V÷RÊ÷ñÂÇÇí«¬Ç”“fˆ«V÷RÊ÷ÖÇÇííbbá¢”“fˆ«V÷RÊ÷ñÂ¢Çí«¬¢”“fˆ«V÷RÊ÷Ö¢Çíì∞–¢––†–¢&ófFR7FFñ2&ˆˆ∆V‚6Ü˜V∆EvñÊF˜rÜñÁBÇ¿–¢ñÁBí¿–¢ñÁB¢¿–¢Ü˜W6Ufˆ«V÷Rfˆ«V÷R¿–¢ñÁB&6Uí¿–¢&∆ˆ6¥f6Rf6ñÊr¿–¢&ˆˆ∆V‚g&ˆÁEfˆ«V÷R¿–¢&ˆˆ∆V‚Gvı7F˜'íí∞–¢ñÁB&V∆FófUí“í“&6Uì∞–¢&ˆˆ∆V‚w&˜VÊEvñÊF˜t&ÊB“&V∆FófUí”“"«¬&V∆FófUí”“3∞–¢&ˆˆ∆V‚WW%vñÊF˜t&ÊB“Gvı7F˜'íbbá&V∆FófUí”“R«¬&V∆FófUí”“bì∞–¢ñbÇw&˜VÊEvñÊF˜t&ÊBbbWW%vñÊF˜t&ÊBí∞–¢&WGW&‚f«6S∞–¢––†–¢&ˆˆ∆V‚ˆ‰Ê˜'FÖ6˜WFÖv∆¬“¢”“fˆ«V÷RÊ÷ñÂ¢Çí«¬¢”“fˆ«V÷RÊ÷Ö¢Çì∞–¢&ˆˆ∆V‚ˆ‰V7EvW7Ev∆¬“Ç”“fˆ«V÷RÊ÷ñÂÇÇí«¬Ç”“fˆ«V÷RÊ÷ÖÇÇì∞–¢ñbÇˆ‰Ê˜'FÖ6˜WFÖv∆¬bbˆ‰V7EvW7Ev∆¬í∞–¢&WGW&‚f«6S∞–¢––†–¢&ˆˆ∆V‚g&ˆÁDf6R“7vóF6ÇÜf6ñÊrí∞–¢66R‰ı%DÇ”‚¢”“fˆ«V÷RÊ÷ñÂ¢Çì∞–¢66R4ıUDÇ”‚¢”“fˆ«V÷RÊ÷Ö¢Çì∞–¢66RT5B”‚Ç”“fˆ«V÷RÊ÷ÖÇÇì∞–¢66RtU5B”‚Ç”“fˆ«V÷RÊ÷ñÂÇÇì∞–¢FVfV«B”‚f«6S∞–¢”∞–†–¢ÚÚ,:ó6W'fRVÊR&ñRFRG&ˆó2&∆ˆ72WF˜W"FR∆˜'FR¬í6ˆ◊&ó2˜W –¢ÚÚ∆W2f:vFW2W7Bˆ˜VW7BVí‚|:óFñVÁBW&fÁB2&˜L:ñ|:ñW2‡–¢ñbÜg&ˆÁEfˆ«V÷Rbbg&ˆÁDf6Rbb&V∆FófUí√“2í∞–¢ñÁB∆FW&ƒFó7FÊ6R“f6ñÊr”“&∆ˆ6¥f6R‰‰ı%DÇ«¬f6ñÊr”“&∆ˆ6¥f6RÂ4ıUDÄ–¢Ú÷FÇÊ'2áÇ“fˆ«V÷RÊ6VÁFW%ÇÇíê–¢¢÷FÇÊ'2á¢“fˆ«V÷RÊ6VÁFW%¢Çíì∞–¢ñbÜ∆FW&ƒFó7FÊ6R√“í∞–¢&WGW&‚f«6S∞–¢––¢––†–¢ñbÜˆ‰Ê˜'FÖ6˜WFÖv∆¬í∞–¢&WGW&‚ó5vñÊF˜t&íÄ–¢Ç“fˆ«V÷RÊ÷ñÂÇÇí¿–¢fˆ«V÷RÊfˆ˜G&ñÁEvñGFÇÇê–¢ì∞–¢––¢&WGW&‚ó5vñÊF˜t&íÄ–¢¢“fˆ«V÷RÊ÷ñÂ¢Çí¿–¢fˆ«V÷RÊfˆ˜G&ñÁDFWFÇÇê–¢ì∞–¢––†–¢Ú¢†–¢¢,:ó'FóBFW2&ñW27ñ‹:óG&óVW26Á2L:óVÊG&RFR∆&óL:íGR,:'Fñ÷VÁB‡–¢¢–¢&ófFR7FFñ2&ˆˆ∆V‚ó5vñÊF˜t&íÜñÁB∆ˆ6ƒ6ˆ˜&FñÊFR¬ñÁB∆VÊwFÇí∞–¢ñbÜ∆VÊwFÇ¬Rí∞–¢&WGW&‚f«6S∞–¢––†–¢ñÁBfó'7B“#∞–¢ñÁB∆7B“∆VÊwFÇ“3∞–¢ñbÜ∆ˆ6ƒ6ˆ˜&FñÊFR”“fó'7B«¬∆ˆ6ƒ6ˆ˜&FñÊFR”“∆7Bí∞–¢&WGW&‚G'VS∞–¢––†–¢&WGW&‚∆VÊwFÇ„“bb∆ˆ6ƒ6ˆ˜&FñÊFR”“Ü∆VÊwFÇ“íÚ#∞–¢––†–¢&ófFR7FFñ2&∆ˆ6¥f6R˜WGv&BÜñÁBÇ¬ñÁB¢¬Ü˜W6Ufˆ«V÷Rfˆ«V÷Rí∞–¢ñbá¢”“fˆ«V÷RÊ÷ñÂ¢Çíí&WGW&‚&∆ˆ6¥f6R‰‰ı%DÉ∞–¢ñbá¢”“fˆ«V÷RÊ÷Ö¢Çíí&WGW&‚&∆ˆ6¥f6RÂ4ıUDÉ∞–¢ñbáÇ”“fˆ«V÷RÊ÷ñÂÇÇíí&WGW&‚&∆ˆ6¥f6RÂtU5C∞–¢&WGW&‚&∆ˆ6¥f6R‰T5C∞–¢––†–¢&ófFR7FFñ2Ü˜W6Ufˆ«V÷RÊÊWÑf˜"ÑÜ˜W6Ufˆ«V÷R÷ñ‚¬∆˜E∆‚∆˜Bí∞–¢ñbÇ∆˜BÊÜ5vñÊrÇíí∞–¢&WGW&‚ÁV∆√∞–¢––†–¢&∆ˆ6¥f6RvñÊu6ñFR“∆˜BÁvñÊu6ñFRÇì∞–¢&∆ˆ6¥f6Rg&ˆÁB“∆˜BÊf6ñÊrÇì∞–¢ñÁBÊÊWÑÜVñváB“÷FÇÊ÷ÇÉ2¬÷ñ‚Áv∆ƒÜVñváBÇí“ì∞–†–¢ñbÜg&ˆÁB”“&∆ˆ6¥f6R‰‰ı%DÇ«¬g&ˆÁB”“&∆ˆ6¥f6RÂ4ıUDÇí∞–¢ñÁB÷ñÂÇ“vñÊu6ñFR”“&∆ˆ6¥f6RÂtU5@–¢Ú÷ñ‚Ê÷ñÂÇÇí“0–¢¢÷ñ‚Ê÷ÖÇÇì∞–¢ñÁB÷ñÂ¢“g&ˆÁB”“&∆ˆ6¥f6R‰‰ı%DÄ–¢Ú÷ñ‚Ê÷Ö¢Çí“@–¢¢÷ñ‚Ê÷ñÂ¢Çì∞–¢&WGW&‚ÊWrÜ˜W6Ufˆ«V÷RÜ÷ñÂÇ¬÷ñÂ¢¬B¬R¬ÊÊWÑÜVñváB¬&ˆˆe7Gñ∆RÂ4ÑTBì∞–¢––†–¢ñÁB÷ñÂÇ“g&ˆÁB”“&∆ˆ6¥f6R‰T5@–¢Ú÷ñ‚Ê÷ñÂÇÇê–¢¢÷ñ‚Ê÷ÖÇÇí“C∞–¢ñÁB÷ñÂ¢“vñÊu6ñFR”“&∆ˆ6¥f6R‰‰ı%DÄ–¢Ú÷ñ‚Ê÷ñÂ¢Çí“0–¢¢÷ñ‚Ê÷Ö¢Çì∞–¢&WGW&‚ÊWrÜ˜W6Ufˆ«V÷RÜ÷ñÂÇ¬÷ñÂ¢¬R¬B¬ÊÊWÑÜVñváB¬&ˆˆe7Gñ∆RÂ4ÑTBì∞–¢––†–¢&ófFR7FFñ2ˆñÁB∆ˆ6≈ˆñÁBÜñÁB6VÁFW%Ç¿–¢ñÁB6VÁFW%¢¿–¢&∆ˆ6¥f6Rg&ˆÁB¿–¢ñÁB∆FW&¬¿–¢ñÁBf˜'v&Bí∞–¢&∆ˆ6¥f6R6fTg&ˆÁB“g&ˆÁB”“&∆ˆ6¥f6R‰‰ı%DÄ–¢«¬g&ˆÁB”“&∆ˆ6¥f6RÂ4ıUDÄ–¢«¬g&ˆÁB”“&∆ˆ6¥f6R‰T5@–¢«¬g&ˆÁB”“&∆ˆ6¥f6RÂtU5@–¢Úg&ˆÁ@–¢¢&∆ˆ6¥f6RÂ4ıUDÉ∞–¢&∆ˆ6¥f6R&ñváB“fñ∆∆vU7Gñ∆RÁ&ñváDˆbá6fTg&ˆÁBì∞–¢&WGW&‚ÊWrˆñÁBÄ–¢6VÁFW%Ç≤&ñváBÊvWD÷ˆEÇÇí¢∆FW&¬≤6fTg&ˆÁBÊvWD÷ˆEÇÇí¢f˜'v&B¿–¢6VÁFW%¢≤&ñváBÊvWD÷ˆE¢Çí¢∆FW&¬≤6fTg&ˆÁBÊvWD÷ˆE¢Çí¢f˜'v&@–¢ì∞–¢––†–¢&ófFR7FFñ2ˆñÁB∆ˆ6≈ˆñÁBÑÜ˜W6Ufˆ«V÷Rfˆ«V÷R¿–¢&∆ˆ6¥f6Rg&ˆÁB¿–¢ñÁB∆FW&¬¿–¢ñÁBf˜'v&Bí∞–¢&WGW&‚∆ˆ6≈ˆñÁBÄ–¢fˆ«V÷RÊ6VÁFW%ÇÇí¿–¢fˆ«V÷RÊ6VÁFW%¢Çí¿–¢g&ˆÁB¿–¢∆FW&¬¿–¢f˜'v&@–¢ì∞–¢––†–¢&ófFR7FFñ2fˆñB∆6T∆ˆ6¬Ñ∆ó7C≈'VÊÊ&∆S‚F6∑2¿–¢FW'&ñ‰÷ÊvW"Â6WD&∆ˆ6≤6"¿–¢ñÁB˜&ñvñÂÇ¿–¢ñÁBí¿–¢ñÁB˜&ñvñÂ¢¿–¢&∆ˆ6¥f6Rg&ˆÁB¿–¢ñÁB∆FW&¬¿–¢ñÁBf˜'v&B¿–¢÷FW&ñ¬÷FW&ñ¬í∞–¢&∆ˆ6¥f6R&ñváB“fñ∆∆vU7Gñ∆RÁ&ñváDˆbÜg&ˆÁBì∞–¢ñÁBÇ“˜&ñvñÂÇ≤&ñváBÊvWD÷ˆEÇÇí¢∆FW&¬≤g&ˆÁBÊvWD÷ˆEÇÇí¢f˜'v&C∞–¢ñÁB¢“˜&ñvñÂ¢≤&ñváBÊvWD÷ˆE¢Çí¢∆FW&¬≤g&ˆÁBÊvWD÷ˆE¢Çí¢f˜'v&C∞–¢∆6RáF6∑2¬6"¬Ç¬í¬¢¬÷FW&ñ¬ì∞–¢––†–¢&ófFR7FFñ2÷FW&ñ¬7&˜f˜"Ñ∆ó7Cƒ÷FW&ñ√‚7&˜2¬&ÊFˆ“&ÊFˆ“¬ñÁBGÇ¬ñÁBG¢í∞–¢ñbÜ7&˜2”“ÁV∆¬«¬7&˜2Êó4V◊GíÇíí∞–¢&WGW&‚÷FW&ñ¬ÂtÑTC∞–¢––¢÷FW&ñ¬6VVB“7&˜2ÊvWBÑ÷FÇÊf∆ˆ˜$÷ˆBÜGÇ¢r≤G¢¢≤&ÊFˆ“ÊÊWáDñÁBÉBí¬7&˜2Á6ó¶RÇííì∞–¢&WGW&‚7vóF6Çá6VVBí∞–¢66RtÑTEı4TTE2”‚÷FW&ñ¬ÂtÑTC∞–¢66R4%$ıB”‚÷FW&ñ¬‰4%$ıE3∞–¢66RıDDÚ”‚÷FW&ñ¬ÂıDDÙU3∞–¢66R$TUE$ÙıEı4TTE2”‚÷FW&ñ¬‰$TUE$ÙıE3∞–¢FVfV«B”‚÷FW&ñ¬ÂtÑTC∞–¢”∞–¢––†–¢&ófFR7FFñ2÷FW&ñ¬÷óÜVDf˜VÊFFñˆ‚Öfñ∆∆vU7Gñ∆RÂ∆WGFR∆WGFR¬ñÁBÇ¬ñÁB¢í∞–¢&WGW&‚÷FÇÊf∆ˆ˜$÷ˆBáÇ¢3≤¢¢r¬Bí”“Ú∆WGFRÊf˜VÊFFñˆ‰66VÁBÇí¢∆WGFRÊf˜VÊFFñˆÂ&ñ÷'íÇì∞–¢––†–¢&ófFR7FFñ2fˆñB∆6T&VBÑ∆ó7C≈'VÊÊ&∆S‚F6∑2¿–¢v˜&∆Bv˜&∆B¿–¢FW'&ñ‰÷ÊvW"Â6WD&∆ˆ6≤6"¿–¢ñÁBÇ¿–¢ñÁBí¿–¢ñÁB¢¿–¢÷FW&ñ¬&VD÷FW&ñ¬¿–¢&∆ˆ6¥f6Rf6ñÊrí∞–¢ñÁBÜVEÇ“Ç≤f6ñÊrÊvWD÷ˆEÇÇì∞–¢ñÁBÜVE¢“¢≤f6ñÊrÊvWD÷ˆE¢Çì∞–¢∆6RáF6∑2¬6"¬Ç¬í¬¢¬&VD÷FW&ñ¬ì∞–¢∆6RáF6∑2¬6"¬ÜVEÇ¬í¬ÜVE¢¬&VD÷FW&ñ¬ì∞–¢ñbáv˜&∆B“ÁV∆¬í∞–¢F6∑2ÊFBÇÇí”‚fñ∆∆vU7Gñ∆RÁ6WD&VBáv˜&∆B¬Ç¬í¬¢¬&VD÷FW&ñ¬¬f6ñÊr¬&VBÂ'B‰dÙıBíì∞–¢F6∑2ÊFBÇÇí”‚fñ∆∆vU7Gñ∆RÁ6WD&VBáv˜&∆B¬ÜVEÇ¬í¬ÜVE¢¬&VD÷FW&ñ¬¬f6ñÊr¬&VBÂ'B‰ÑTBíì∞–¢––¢––†–¢&ófFR7FFñ2fˆñBvFRÑ∆ó7C≈'VÊÊ&∆S‚F6∑2¿–¢v˜&∆Bv˜&∆B¿–¢ñÁBÇ¿–¢ñÁBí¿–¢ñÁB¢¿–¢÷FW&ñ¬÷FW&ñ¬¿–¢&∆ˆ6¥f6Rf6ñÊr¿–¢&ˆˆ∆V‚˜V‚¿–¢&ˆˆ∆V‚ñÂv∆¬í∞–¢ñbáv˜&∆B“ÁV∆¬í∞–¢F6∑2ÊFBÇÇí”‚fñ∆∆vU7Gñ∆RÁ6WDvFRáv˜&∆B¬Ç¬í¬¢¬÷FW&ñ¬¬f6ñÊr¬˜V‚¬ñÂv∆¬íì∞–¢––¢––†–¢&ófFR7FFñ2fˆñB6∆"Ñ∆ó7C≈'VÊÊ&∆S‚F6∑2¿–¢v˜&∆Bv˜&∆B¿–¢FW'&ñ‰÷ÊvW"Â6WD&∆ˆ6≤6"¿–¢ñÁBÇ¿–¢ñÁBí¿–¢ñÁB¢¿–¢÷FW&ñ¬÷FW&ñ¬¿–¢6∆"ÂGóRGóRí∞–¢∆6RáF6∑2¬6"¬Ç¬í¬¢¬÷FW&ñ¬ì∞–¢ñbáv˜&∆B“ÁV∆¬í∞–¢F6∑2ÊFBÇÇí”‚fñ∆∆vU7Gñ∆RÁ6WE6∆"áv˜&∆B¬Ç¬í¬¢¬÷FW&ñ¬¬GóRíì∞–¢––¢––†–¢&ófFR7FFñ2fˆñBG&Fˆ˜"Ñ∆ó7C≈'VÊÊ&∆S‚F6∑2¿–¢v˜&∆Bv˜&∆B¿–¢ñÁBÇ¿–¢ñÁBí¿–¢ñÁB¢¿–¢÷FW&ñ¬÷FW&ñ¬¿–¢&∆ˆ6¥f6Rf6ñÊr¿–¢&ˆˆ∆V‚˜V‚¿–¢&ó6V7FVB‰Ü∆bÜ∆bí∞–¢ñbáv˜&∆B“ÁV∆¬í∞–¢F6∑2ÊFBÇÇí”‚fñ∆∆vU7Gñ∆RÁ6WEG&Fˆ˜"áv˜&∆B¬Ç¬í¬¢¬÷FW&ñ¬¬f6ñÊr¬˜V‚¬Ü∆bíì∞–¢––¢––†–¢&ófFR7FFñ2fˆñB7Fó"Ñ∆ó7C≈'VÊÊ&∆S‚F6∑2¿–¢v˜&∆Bv˜&∆B¿–¢FW'&ñ‰÷ÊvW"Â6WD&∆ˆ6≤6"¿–¢ñÁBÇ¿–¢ñÁBí¿–¢ñÁB¢¿–¢÷FW&ñ¬÷FW&ñ¬¿–¢&∆ˆ6¥f6Rf6ñÊrí∞–¢∆6RáF6∑2¬6"¬Ç¬í¬¢¬÷FW&ñ¬ì∞–¢ñbáv˜&∆B“ÁV∆¬í∞–¢F6∑2ÊFBÇÇí”‚fñ∆∆vU7Gñ∆RÁ6WE7Fó"áv˜&∆B¬Ç¬í¬¢¬÷FW&ñ¬¬f6ñÊr¬7Fó'2‰Ü∆b‰$ıEDÙ“¬7Fó'2Â6ÜRÂ5E$îtÖBíì∞–¢––¢––†–¢&ófFR7FFñ2fˆñB∆6RÑ∆ó7C≈'VÊÊ&∆S‚F6∑2¬FW'&ñ‰÷ÊvW"Â6WD&∆ˆ6≤6"¬ñÁBÇ¬ñÁBí¬ñÁB¢¬÷FW&ñ¬÷FW&ñ¬í∞–¢F6∑2ÊFBÇÇí”‚6"Á6WBáÇ¬í¬¢¬÷FW&ñ¬íì∞–¢––†–¢&ófFR&V6˜&BˆñÁBÜñÁBÇ¬ñÁB¢í∑––†–¢&ófFR&V6˜&BÜ˜W6Ufˆ«V÷RÜñÁB÷ñÂÇ¬ñÁB÷ñÂ¢¬ñÁBfˆ˜G&ñÁEvñGFÇ¬ñÁBfˆ˜G&ñÁDFWFÇ¬ñÁBv∆ƒÜVñváB¬&ˆˆe7Gñ∆R&ˆˆe7Gñ∆Rí∞–¢ñÁB÷ÖÇÇí≤&WGW&‚÷ñÂÇ≤fˆ˜G&ñÁEvñGFÇ“≤––¢ñÁB÷Ö¢Çí≤&WGW&‚÷ñÂ¢≤fˆ˜G&ñÁDFWFÇ“≤––¢ñÁB6VÁFW%ÇÇí≤&WGW&‚Ü÷ñÂÇ≤÷ÖÇÇííÚ#≤––¢ñÁB6VÁFW%¢Çí≤&WGW&‚Ü÷ñÂ¢≤÷Ö¢ÇííÚ#≤––¢––ß––
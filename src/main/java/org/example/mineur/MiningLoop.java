package org.example.mineur;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.example.TeleportUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Boucle unique d'une session, pilotée par une petite machine à états.
 */
public final class MiningLoop extends BukkitRunnable {

    private static final double ANIMATION_STAGES = 2.0D;
    private static final double BREAK_STAGE = 1.0D;
    private static final double DEPOSIT_STAGE = 1.0D;
    private static final int STORAGE_RECHECK_TICKS = 20;

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
    private final ItemStack miningTool;
    private final Predicate<Block> breakPermission;
    private final Consumer<Block> decorationCallback;
    private final Runnable completionCallback;
    private final Runnable storageBlockedCallback;
    private final Runnable storageFreedCallback;
    private final Consumer<Block> protectionBlockedCallback;
    private final Consumer<Exception> failureCallback;
    private final boolean applyPhysics;
    private final double progressPerTick;

    private boolean storageBlockedNotified;
    private int storageRecheckCooldown;

    private Block current;
    private Material currentType;
    private List<ItemStack> currentDrops = List.of();
    private Phase phase = Phase.IDLE;
    private double phaseProgress = 0.0D;

    public MiningLoop(JavaPlugin plugin,
                      MiningSessionState state,
                      MiningIterator iterator,
                      InventoryRouter router,
                      Villager miner,
                      ItemStack miningTool,
                      Predicate<Block> breakPermission,
                      Consumer<Block> decorationCallback,
                      Runnable completionCallback,
                      Runnable storageBlockedCallback,
                      Runnable storageFreedCallback,
                      Consumer<Block> protectionBlockedCallback,
                      Consumer<Exception> failureCallback,
                      boolean applyPhysics,
                      boolean initiallyBlocked,
                      double progressPerTick) {
        this.plugin = plugin;
        this.state = state;
        this.iterator = iterator;
        this.router = router;
        this.miner = miner;
        this.miningTool = miningTool != null ? miningTool.clone() : null;
        this.breakPermission = breakPermission;
        this.decorationCallback = decorationCallback;
        this.completionCallback = completionCallback;
        this.storageBlockedCallback = storageBlockedCallback;
        this.storageFreedCallback = storageFreedCallback;
        this.protectionBlockedCallback = protectionBlockedCallback;
        this.failureCallback = failureCallback;
        this.applyPhysics = applyPhysics;
        this.storageBlockedNotified = initiallyBlocked;
        this.progressPerTick = Double.isFinite(progressPerTick)
                ? Math.max(0.01D, progressPerTick)
                : 0.01D;
    }

    @Override
    public void run() {
        try {
            runSafely();
        } catch (Exception exception) {
            cancelAndRollback();
            state.paused = true;
            plugin.getLogger().severe("[Mineur] Session " + state.id
                    + " suspendue après une erreur : " + exception.getMessage());
            if (failureCallback != null) {
                failureCallback.accept(exception);
            }
        }
    }

    private void runSafely() {
        if (state.paused) {
            return;
        }
        if (miner == null || miner.isDead() || !miner.isValid()) {
            /*
             * La disparition du PNJ n'est jamais une preuve de fin de parcours.
             * La traiter comme une complétion pouvait enchaîner vers le tunnel
             * ou supprimer la session alors que des blocs restaient à miner.
             */
            throw new IllegalStateException("Le PNJ mineur n'est plus disponible.");
        }

        /*
         * Une fois le bloc cassé, la phase DEPOSITING doit toujours pouvoir se
         * terminer, même si le dernier dépôt vient de remplir exactement le
         * stockage. Recontrôler sa capacité ici bloquerait sinon la machine à
         * états sur un bloc déjà disparu.
         */
        if (current != null
                && (phase == Phase.ANIMATING || phase == Phase.BREAKING)
                && !checkStorageAvailability()) {
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
            cancelTaskSafely();
            state.pendingCursor = null;
            if (completionCallback != null) {
                completionCallback.run();
            }
            return;
        }

        /*
         * La fin du parcours est évaluée avant le stockage. Une session dont le
         * dernier coffre a disparu après le dernier bloc ne doit pas rester
         * éternellement affichée « en attente de stockage ».
         *
         * Avec une liste de drops vide, ce contrôle vérifie uniquement qu'un
         * conteneur réel et autorisé existe, sans avancer le curseur.
         */
        if (!checkStorageAvailability()) {
            return;
        }

        /*
         * Le checkpoint doit être publié AVANT d'avancer l'itérateur. Ainsi,
         * toute exception pendant next(), la validation du bloc ou le calcul
         * des drops remettra réellement le curseur avant la coordonnée visée.
         */
        MiningCursor checkpoint = iterator.cursor().copy();
        state.pendingCursor = checkpoint;

        Block candidate = iterator.next();
        if (candidate == null || !MiningBlockPolicy.isMineable(candidate)) {
            /*
             * next() peut ne rien retourner après avoir consommé un budget de
             * blocs vides/protégés. Ce parcours est volontairement validé.
             */
            state.pendingCursor = null;
            return;
        }

        current = candidate;
        currentType = candidate.getType();
        currentDrops = MiningBlockPolicy.computeDrops(candidate, miningTool);
        phase = Phase.ANIMATING;
        phaseProgress = 0.0D;
        moveMinerTowards(current);

        // La phase reste ANIMATING pendant l'attente de stockage : le bloc
        // courant ne peut donc pas être remplacé par le candidat suivant.
        checkStorageAvailability();
    }

    private void handleAnimating() {
        if (!refreshCurrentBlock()) {
            finishCurrentWithoutBreaking();
            return;
        }

        World world = current.getWorld();
        Location blockCenter = current.getLocation().add(0.5, 0.5, 0.5);
        orientMinerTowards(blockCenter);
        miner.swingMainHand();

        boolean ore = MiningBlockPolicy.isOre(currentType);
        if (ore) {
            world.spawnParticle(Particle.CRIT, blockCenter, 10, 0.3, 0.3, 0.3, 0.1);
        } else {
            world.spawnParticle(
                    Particle.BLOCK,
                    blockCenter,
                    10,
                    0.3, 0.3, 0.3,
                    0.1,
                    current.getBlockData()
            );
        }
        world.playSound(
                blockCenter,
                ore ? Sound.ENTITY_VILLAGER_WORK_TOOLSMITH : Sound.BLOCK_STONE_HIT,
                0.6F,
                1.0F
        );

        phaseProgress += progressPerTick;
        if (phaseProgress >= ANIMATION_STAGES) {
            phase = Phase.BREAKING;
            phaseProgress = 0.0D;
        }
    }

    private void handleBreaking() {
        if (!refreshCurrentBlock()) {
            finishCurrentWithoutBreaking();
            return;
        }
        if (!checkStorageAvailability()) {
            return;
        }

        miner.swingMainHand();
        phaseProgress += progressPerTick;
        if (phaseProgress < BREAK_STAGE) {
            return;
        }

        if (breakPermission != null && !breakPermission.test(current)) {
            Block blocked = current;
            cancelAndRollback();
            state.paused = true;
            if (protectionBlockedCallback != null) {
                protectionBlockedCallback.accept(blocked);
            }
            return;
        }

        /*
         * Un plugin appelé par l'événement synthétique peut modifier le bloc.
         * On recalcule donc ses données, ses drops et la capacité de stockage
         * juste avant la mutation définitive.
         */
        if (!refreshCurrentBlock()) {
            finishCurrentWithoutBreaking();
            return;
        }
        if (!checkStorageAvailability()) {
            return;
        }

        World world = current.getWorld();
        Location location = current.getLocation();
        BlockData brokenData = current.getBlockData();
        Material brokenType = current.getType();
        List<ItemStack> drops = new ArrayList<>(currentDrops);

        current.setType(Material.AIR, applyPhysics);

        /*
         * À partir de cette ligne, le bloc n'existe plus : la transaction est
         * validée immédiatement. Une erreur cosmétique ou de décoration ne doit
         * surtout pas restaurer le curseur vers une case désormais vide.
         */
        state.pendingCursor = null;
        state.minerY = miner.getLocation().getY();

        depositDrops(world, location, drops);
        playBreakEffects(world, location, brokenData, brokenType);
        runDecorationSafely(current);

        phase = Phase.DEPOSITING;
        phaseProgress = 0.0D;
    }

    private void depositDrops(World world, Location fallback, List<ItemStack> drops) {
        if (drops == null || drops.isEmpty()) {
            return;
        }

        List<ItemStack> leftovers;
        try {
            leftovers = router != null ? router.deposit(drops) : new ArrayList<>(drops);
        } catch (RuntimeException exception) {
            /*
             * InventoryRouter contient déjà les erreurs par inventaire. Cette
             * garde ultime conserve néanmoins les ressources si une
             * implémentation d'inventaire tierce viole le contrat Bukkit.
             */
            leftovers = new ArrayList<>(drops);
            plugin.getLogger().warning("[Mineur] Dépôt impossible pour la session "
                    + state.id + " : " + exception.getMessage());
        }

        if (leftovers.isEmpty()) {
            return;
        }

        try {
            if (router != null) {
                router.dropOnGround(world, fallback, leftovers);
            } else {
                for (ItemStack item : leftovers) {
                    world.dropItem(fallback.clone().add(0.5D, 0.5D, 0.5D), item);
                }
            }
            plugin.getLogger().warning("[Mineur] Stockage modifié pendant le dépôt de la session "
                    + state.id + " ; " + leftovers.size() + " pile(s) déposée(s) au sol.");
        } catch (RuntimeException exception) {
            /*
             * Le bloc est déjà cassé : on journalise sans faire remonter
             * l'exception, afin de ne jamais rejouer la même coordonnée.
             */
            plugin.getLogger().severe("[Mineur] Impossible de matérialiser "
                    + leftovers.size() + " pile(s) de la session " + state.id
                    + " : " + exception.getMessage());
        }
    }

    private void playBreakEffects(World world,
                                  Location location,
                                  BlockData brokenData,
                                  Material brokenType) {
        try {
            world.spawnParticle(
                    Particle.BLOCK,
                    location.clone().add(0.5, 0.5, 0.5),
                    20,
                    0.3, 0.3, 0.3,
                    0.1,
                    brokenData
            );
            world.playSound(
                    location,
                    MiningBlockPolicy.isOre(brokenType)
                            ? Sound.ENTITY_VILLAGER_WORK_TOOLSMITH
                            : Sound.BLOCK_STONE_BREAK,
                    0.7F,
                    1.0F
            );
        } catch (RuntimeException exception) {
            plugin.getLogger().fine("[Mineur] Effet visuel ignoré pour la session "
                    + state.id + " : " + exception.getMessage());
        }
    }

    private void runDecorationSafely(Block brokenBlock) {
        if (decorationCallback == null) {
            return;
        }
        try {
            decorationCallback.accept(brokenBlock);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("[Mineur] Décoration ignorée pour la session "
                    + state.id + " : " + exception.getMessage());
        }
    }

    private void handleDepositing() {
        phaseProgress += progressPerTick;
        if (phaseProgress >= DEPOSIT_STAGE) {
            clearCurrent();
            phase = Phase.IDLE;
            phaseProgress = 0.0D;
        }
    }

    private boolean refreshCurrentBlock() {
        if (current == null || !MiningBlockPolicy.isMineable(current)) {
            return false;
        }

        /*
         * Deux blocs de même Material peuvent avoir un BlockData différent
         * (âge, état, contenu métier, etc.). Les drops sont donc recalculés à
         * chaque validation finale et pas seulement lors d'un changement de type.
         */
        currentType = current.getType();
        currentDrops = MiningBlockPolicy.computeDrops(current, miningTool);
        return true;
    }

    private boolean checkStorageAvailability() {
        if (storageBlockedNotified && storageRecheckCooldown > 0) {
            storageRecheckCooldown--;
            return false;
        }

        boolean available = router != null
                && router.hasTargets()
                && router.canFitAll(currentDrops);
        if (!available) {
            notifyStorageBlocked();
            return false;
        }

        storageRecheckCooldown = 0;
        if (storageBlockedNotified) {
            storageBlockedNotified = false;
            if (storageFreedCallback != null) {
                storageFreedCallback.run();
            }
        }
        return true;
    }

    private void notifyStorageBlocked() {
        storageRecheckCooldown = STORAGE_RECHECK_TICKS;
        if (storageBlockedNotified) {
            return;
        }
        storageBlockedNotified = true;
        if (storageBlockedCallback != null) {
            storageBlockedCallback.run();
        }
    }

    private void finishCurrentWithoutBreaking() {
        state.pendingCursor = null;
        clearCurrent();
        phase = Phase.IDLE;
        phaseProgress = 0.0D;
    }

    private void clearCurrent() {
        current = null;
        currentType = null;
        currentDrops = List.of();
    }

    private void moveMinerTowards(Block target) {
        Location destination = findSafeDestination(target);

        /*
         * Le PNJ du mode mineur a volontairement son IA désactivée afin de ne
         * pas fuir, ouvrir des portes ou choisir un métier. Un pathfinder peut
         * accepter la destination tout en ne déplaçant jamais une entité NoAI ;
         * la téléportation par bloc est donc déterministe et sans jitter.
         */
        if (!TeleportUtils.safeTeleport(miner, destination)) {
            /*
             * Continuer malgré une téléportation annulée donnerait au joueur un
             * PNJ immobile qui mine à distance. L'exception est interceptée par
             * la boucle avant toute casse et la coordonnée reste rejouable.
             */
            throw new IllegalStateException(
                    "Le déplacement du PNJ mineur a été refusé par le serveur."
            );
        }
        /*
         * Un listener peut ajuster légèrement la destination. Persister la
         * position réellement atteinte évite de faire réapparaître le PNJ à une
         * coordonnée fictive après un redémarrage.
         */
        state.minerY = miner.getLocation().getY();
    }

    /**
     * Cherche un emplacement de deux blocs de haut près de la cible.
     *
     * <p>Dans un tunnel, le bloc situé juste au-dessus de la cible peut encore
     * être plein. Cette recherche évite que le PNJ apparaisse dans la roche
     * alors qu'une case déjà excavée existe à côté ou derrière lui.</p>
     */
    private Location findSafeDestination(Block target) {
        World world = target.getWorld();
        int[][] horizontalOffsets = {
                {0, 0},
                {-1, 0}, {1, 0}, {0, -1}, {0, 1},
                {-1, -1}, {-1, 1}, {1, -1}, {1, 1},
                {-2, 0}, {2, 0}, {0, -2}, {0, 2},
                {-2, -1}, {-2, 1}, {2, -1}, {2, 1},
                {-1, -2}, {1, -2}, {-1, 2}, {1, 2},
                {-3, 0}, {3, 0}, {0, -3}, {0, 3}
        };

        int minimumY = world.getMinHeight();
        int maximumFeetY = world.getMaxHeight() - 2;
        for (int verticalOffset = -2; verticalOffset <= 8; verticalOffset++) {
            int feetY = target.getY() + verticalOffset;
            if (feetY < minimumY || feetY > maximumFeetY) {
                continue;
            }
            for (int[] offset : horizontalOffsets) {
                if (verticalOffset == 0 && offset[0] == 0 && offset[1] == 0) {
                    continue;
                }

                Location safe = safeCenteredLocation(
                        world,
                        target.getX() + offset[0],
                        feetY,
                        target.getZ() + offset[1]
                );
                if (safe != null) {
                    return safe;
                }
            }
        }

        /*
         * Conserver la position courante est préférable à téléporter le PNJ
         * dans la roche. Ce chemin sert notamment pendant l'effondrement
         * temporaire de sable ou de gravier avec la physique activée.
         */
        Location currentLocation = miner.getLocation();
        if (currentLocation.getWorld() != null && currentLocation.getWorld().equals(world)) {
            Location safeCurrent = safeCenteredLocation(
                    world,
                    currentLocation.getBlockX(),
                    currentLocation.getBlockY(),
                    currentLocation.getBlockZ()
            );
            if (safeCurrent != null) {
                return safeCurrent;
            }
        }

        /*
         * Dernier recours rare : rechercher la surface de cette colonne. Cette
         * requête ne survient qu'après l'échec de la recherche locale bornée.
         */
        if (world.isChunkLoaded(target.getX() >> 4, target.getZ() >> 4)) {
            int surfaceY = Math.max(
                    minimumY,
                    Math.min(
                            maximumFeetY,
                            world.getHighestBlockYAt(target.getX(), target.getZ()) + 1
                    )
            );
            Location surface = safeCenteredLocation(
                    world,
                    target.getX(),
                    surfaceY,
                    target.getZ()
            );
            if (surface != null) {
                return surface;
            }
        }

        /*
         * Miner à distance avec un PNJ coincé dans un bloc est plus trompeur
         * qu'une pause explicite. L'exception est interceptée par la boucle et
         * le checkpoint reste rejouable après intervention de l'administrateur.
         */
        throw new IllegalStateException(
                "Aucun emplacement sûr n'a été trouvé près du bloc à miner."
        );
    }

    private Location safeCenteredLocation(World world, int x, int feetY, int z) {
        if (world == null
                || feetY < world.getMinHeight()
                || feetY >= world.getMaxHeight() - 1
                || !world.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }

        Location location = new Location(world, x + 0.5D, feetY, z + 0.5D);
        if (world.getWorldBorder() != null && !world.getWorldBorder().isInside(location)) {
            return null;
        }

        Block feet = world.getBlockAt(x, feetY, z);
        Block head = world.getBlockAt(x, feetY + 1, z);
        if (!isSafeEntitySpace(feet) || !isSafeEntitySpace(head)) {
            return null;
        }
        return location;
    }

    private boolean isSafeEntitySpace(Block block) {
        if (block == null || !block.isPassable() || block.isLiquid()) {
            return false;
        }
        return switch (block.getType()) {
            case FIRE, SOUL_FIRE, POWDER_SNOW,
                    CACTUS, SWEET_BERRY_BUSH, WITHER_ROSE,
                    NETHER_PORTAL, END_PORTAL, END_GATEWAY -> false;
            default -> true;
        };
    }

    private void orientMinerTowards(Location target) {
        if (miner.isDead()) {
            return;
        }
        Location look = miner.getLocation();
        if (look.getWorld() == null || !look.getWorld().equals(target.getWorld())) {
            return;
        }
        look.setDirection(target.toVector().subtract(look.toVector()));
        miner.setRotation(look.getYaw(), look.getPitch());
    }

    private void cancelTaskSafely() {
        try {
            cancel();
        } catch (IllegalStateException ignored) {
            /*
             * La boucle peut être exécutée directement par un test ou échouer
             * avant son enregistrement auprès du scheduler.
             */
        }
    }

    /**
     * Annule la tâche et remet le curseur avant le bloc non cassé.
     */
    public void cancelAndRollback() {
        state.rollbackPendingCursor();
        clearCurrent();
        phase = Phase.IDLE;
        phaseProgress = 0.0D;
        cancelTaskSafely();
    }
}

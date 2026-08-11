package org.example.village;

import org.bukkit.Location;
import org.bukkit.block.data.BlockData;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * État isolé d'une génération de village.
 *
 * <p>Les blocs d'origine sont mémorisés avant la première écriture. Une
 * annulation restaure donc le terrain, les constructions et la végétation
 * existants au lieu de remplacer aveuglément toute la zone par de l'air.</p>
 */
public final class VillageGenerationSession {
    private final int villageId;
    private final Map<Location, BlockData> originalBlocks = new LinkedHashMap<>();
    private final Set<Location> originalArtificialBlocks = new LinkedHashSet<>();
    private final Set<UUID> generatedEntities = new LinkedHashSet<>();
    private final Set<Location> generatedSpawners = new LinkedHashSet<>();
    private final Map<String, Location> anchors = new LinkedHashMap<>();

    /*
     * Ces marqueurs distinguent les sous-systèmes publiés par cette transaction
     * d'un éventuel état plus ancien portant le même identifiant de village.
     */
    private boolean treasuryRegistered;
    private boolean knightGarrisonRegistered;

    public VillageGenerationSession(int villageId) {
        this.villageId = villageId;
    }

    public int getVillageId() {
        return villageId;
    }

    /**
     * Vue de compatibilité : ce sont toutes les positions modifiées.
     */
    public Set<Location> getPlacedBlocks() {
        return Collections.unmodifiableSet(originalBlocks.keySet());
    }

    public Map<Location, BlockData> getOriginalBlocks() {
        return Collections.unmodifiableMap(originalBlocks);
    }

    public Set<UUID> getGeneratedEntities() {
        return Collections.unmodifiableSet(generatedEntities);
    }

    public Set<Location> getGeneratedSpawners() {
        return Collections.unmodifiableSet(generatedSpawners);
    }

    public Map<String, Location> getAnchors() {
        return anchors;
    }

    public boolean isTreasuryRegistered() {
        return treasuryRegistered;
    }

    public void markTreasuryRegistered() {
        treasuryRegistered = true;
    }

    public boolean isKnightGarrisonRegistered() {
        return knightGarrisonRegistered;
    }

    public void markKnightGarrisonRegistered() {
        knightGarrisonRegistered = true;
    }

    /**
     * Conserve le premier état observé seulement : plusieurs écritures au même
     * emplacement doivent toutes revenir à l'état antérieur à la génération.
     */
    public void rememberOriginal(Location location, BlockData originalData) {
        rememberOriginal(location, originalData, false);
    }

    /**
     * Conserve aussi l'état anti-recyclage du bloc avant sa première
     * modification. Le rollback peut ainsi distinguer un bloc naturel d'un
     * bloc déjà posé par un joueur.
     */
    public void rememberOriginal(
            Location location,
            BlockData originalData,
            boolean artificial) {
        if (location == null || originalData == null) {
            return;
        }

        Location key = blockLocation(location);
        if (originalBlocks.containsKey(key)) {
            return;
        }
        originalBlocks.put(key, originalData.clone());
        if (artificial) {
            originalArtificialBlocks.add(key);
        }
    }

    public boolean wasOriginalArtificial(Location location) {
        return location != null
                && originalArtificialBlocks.contains(blockLocation(location));
    }

    /**
     * Méthode conservée pour les intégrations historiques. Le code de
     * génération moderne doit appeler {@link #rememberOriginal(Location,
     * BlockData)} avant de modifier le bloc.
     */
    public void trackBlock(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        Location key = blockLocation(location);
        originalBlocks.putIfAbsent(key, key.getBlock().getBlockData().clone());
    }

    public void trackEntity(UUID entityId) {
        if (entityId != null) {
            generatedEntities.add(entityId);
        }
    }

    public void trackSpawner(Location location) {
        if (location != null) {
            generatedSpawners.add(blockLocation(location));
        }
    }

    private static Location blockLocation(Location location) {
        return new Location(
                location.getWorld(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );
    }
}

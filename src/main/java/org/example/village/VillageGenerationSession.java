package org.example.village;

import org.bukkit.Location;

import java.util.*;

/**
 * État interne d'une génération de village pour un cycle complet generate/undo.
 */
public final class VillageGenerationSession {
    private final int villageId;
    private final Set<Location> placedBlocks = new LinkedHashSet<>();
    private final Set<UUID> generatedEntities = new LinkedHashSet<>();
    private final Set<Location> generatedSpawners = new LinkedHashSet<>();
    private final Map<String, Location> anchors = new HashMap<>();

    public VillageGenerationSession(int villageId) {
        this.villageId = villageId;
    }

    public int getVillageId() { return villageId; }
    public Set<Location> getPlacedBlocks() { return placedBlocks; }
    public Set<UUID> getGeneratedEntities() { return generatedEntities; }
    public Set<Location> getGeneratedSpawners() { return generatedSpawners; }
    public Map<String, Location> getAnchors() { return anchors; }

    public void trackBlock(Location location) {
        placedBlocks.add(location);
    }

    public void trackEntity(UUID entityId) {
        generatedEntities.add(entityId);
    }

    public void trackSpawner(Location location) {
        generatedSpawners.add(location);
        placedBlocks.add(location);
    }
}

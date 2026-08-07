package org.example.mineur;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Distribue les drops dans les conteneurs encore présents de la session.
 *
 * <p>Les blocs sont résolus à chaque opération : un coffre remplacé par un
 * autre plugin ne reste donc pas considéré comme un stockage valide. Les
 * doubles coffres sont dédupliqués par leurs coordonnées physiques plutôt que
 * par l'identité fragile de l'objet {@link Inventory} renvoyé par le serveur.</p>
 */
public final class InventoryRouter {

    private final List<Block> targets = new ArrayList<>();
    private final Predicate<Container> containerValidator;
    private int roundRobin = 0;

    public InventoryRouter(List<Block> blocks) {
        this(blocks, ignored -> true);
    }

    /**
     * @param containerValidator validation rejouée à chaque accès, notamment
     *                           pour vérifier le PDC de la session.
     */
    public InventoryRouter(List<Block> blocks, Predicate<Container> containerValidator) {
        this.containerValidator = containerValidator != null
                ? containerValidator
                : ignored -> false;
        if (blocks == null) {
            return;
        }
        for (Block block : blocks) {
            if (block != null) {
                targets.add(block);
            }
        }
    }

    /**
     * @return {@code true} si au moins un conteneur exploitable existe encore.
     */
    public boolean hasTargets() {
        return !resolveInventories().isEmpty();
    }

    /**
     * Compatibilité avec les appels historiques. Pour une décision liée à des
     * drops précis, utiliser {@link #canFitAll(List)}.
     */
    public boolean hasFreeSpace() {
        for (Inventory inventory : resolveInventories()) {
            try {
                if (inventory.firstEmpty() >= 0) {
                    return true;
                }
                ItemStack[] storage = inventory.getStorageContents();
                if (storage == null) {
                    continue;
                }
                for (ItemStack stack : storage) {
                    if (stack != null
                            && stack.getType() != Material.AIR
                            && stack.getAmount() < Math.min(
                            stack.getMaxStackSize(),
                            Math.max(1, inventory.getMaxStackSize())
                    )) {
                        return true;
                    }
                }
            } catch (RuntimeException ignored) {
                // Un inventaire invalidé entre deux appels est simplement écarté.
            }
        }
        return false;
    }

    /**
     * Simule l'insertion sans modifier les inventaires.
     *
     * @return {@code true} lorsque tous les objets peuvent être stockés.
     */
    public boolean canFitAll(List<ItemStack> items) {
        List<ItemStack> pending = sanitize(items);
        if (pending.isEmpty()) {
            return true;
        }

        List<InventorySnapshot> snapshots = new ArrayList<>();
        for (Inventory inventory : resolveInventories()) {
            try {
                ItemStack[] contents = inventory.getStorageContents();
                if (contents == null) {
                    continue;
                }
                ItemStack[] copy = new ItemStack[contents.length];
                for (int index = 0; index < contents.length; index++) {
                    copy[index] = contents[index] == null ? null : contents[index].clone();
                }
                snapshots.add(new InventorySnapshot(
                        copy,
                        Math.max(1, inventory.getMaxStackSize())
                ));
            } catch (RuntimeException ignored) {
                /*
                 * Le coffre peut avoir été cassé ou remplacé après sa résolution.
                 * Ne pas compter sa capacité évite un faux positif destructeur.
                 */
            }
        }
        if (snapshots.isEmpty()) {
            return false;
        }

        for (ItemStack item : pending) {
            int remaining = item.getAmount();

            // On remplit d'abord les piles compatibles déjà présentes.
            for (InventorySnapshot snapshot : snapshots) {
                for (ItemStack existing : snapshot.contents()) {
                    if (remaining <= 0) {
                        break;
                    }
                    if (existing == null || existing.getType() == Material.AIR
                            || !existing.isSimilar(item)) {
                        continue;
                    }
                    int maximum = Math.min(
                            existing.getMaxStackSize(),
                            snapshot.maxStackSize()
                    );
                    int accepted = Math.min(
                            remaining,
                            Math.max(0, maximum - existing.getAmount())
                    );
                    existing.setAmount(existing.getAmount() + accepted);
                    remaining -= accepted;
                }
            }

            // Puis on consomme les emplacements vides.
            for (InventorySnapshot snapshot : snapshots) {
                for (int slot = 0; slot < snapshot.contents().length && remaining > 0; slot++) {
                    ItemStack existing = snapshot.contents()[slot];
                    if (existing != null && existing.getType() != Material.AIR) {
                        continue;
                    }
                    int accepted = Math.min(
                            remaining,
                            Math.min(item.getMaxStackSize(), snapshot.maxStackSize())
                    );
                    ItemStack inserted = item.clone();
                    inserted.setAmount(accepted);
                    snapshot.contents()[slot] = inserted;
                    remaining -= accepted;
                }
            }

            if (remaining > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tente de déposer tous les objets dans les conteneurs, en rotation.
     *
     * <p>Une implémentation d'inventaire tierce peut devenir invalide entre la
     * simulation et le dépôt. Son exception est confinée à ce conteneur et les
     * piles concernées restent dans le résultat au lieu de disparaître.</p>
     *
     * @return les objets qui n'ont pas pu être stockés.
     */
    public List<ItemStack> deposit(List<ItemStack> items) {
        List<ItemStack> pending = sanitize(items);
        List<Inventory> inventories = resolveInventories();
        if (pending.isEmpty() || inventories.isEmpty()) {
            return pending;
        }

        int start = Math.floorMod(roundRobin, inventories.size());
        for (int offset = 0; offset < inventories.size() && !pending.isEmpty(); offset++) {
            Inventory inventory = inventories.get((start + offset) % inventories.size());
            List<ItemStack> leftovers = new ArrayList<>();
            for (ItemStack stack : pending) {
                try {
                    Map<Integer, ItemStack> result = inventory.addItem(stack.clone());
                    if (result == null) {
                        /*
                         * Le contrat Bukkit impose une map non nulle. Une
                         * implémentation tierce qui le viole ne doit pas faire
                         * disparaître silencieusement la pile.
                         */
                        leftovers.add(stack.clone());
                        continue;
                    }
                    if (result.isEmpty()) {
                        continue;
                    }
                    for (ItemStack leftover : result.values()) {
                        if (leftover != null
                                && leftover.getType() != Material.AIR
                                && leftover.getAmount() > 0) {
                            leftovers.add(leftover.clone());
                        }
                    }
                } catch (RuntimeException ignored) {
                    leftovers.add(stack.clone());
                }
            }
            pending = leftovers;
        }

        roundRobin = (start + 1) % inventories.size();
        return pending;
    }

    public void dropOnGround(World world, Location fallback, List<ItemStack> items) {
        if (world == null || items == null || items.isEmpty()) {
            return;
        }

        Location dropLocation = null;
        for (Block target : targets) {
            try {
                if (target != null
                        && target.getState() instanceof Container container
                        && isValidContainer(container)) {
                    dropLocation = target.getLocation().add(0.5, 1.1, 0.5);
                    break;
                }
            } catch (RuntimeException ignored) {
                // Le conteneur a pu disparaître entre la casse et le dépôt.
            }
        }
        if (dropLocation == null && fallback != null) {
            dropLocation = fallback.clone();
        }
        if (dropLocation == null) {
            dropLocation = world.getSpawnLocation().add(0, 1.0, 0);
        }

        for (ItemStack item : sanitize(items)) {
            world.dropItem(dropLocation, item);
        }
    }

    private List<ItemStack> sanitize(List<ItemStack> items) {
        List<ItemStack> sanitized = new ArrayList<>();
        if (items == null) {
            return sanitized;
        }
        for (ItemStack item : items) {
            if (item != null && item.getType() != Material.AIR && item.getAmount() > 0) {
                sanitized.add(item.clone());
            }
        }
        return sanitized;
    }

    private List<Inventory> resolveInventories() {
        List<Inventory> inventories = new ArrayList<>();
        Set<String> seenCoordinates = new HashSet<>();
        Set<Inventory> seenIdentities = Collections.newSetFromMap(new IdentityHashMap<>());

        for (Block block : targets) {
            try {
                if (block == null
                        || !(block.getState() instanceof Container container)
                        || !isValidContainer(container)) {
                    continue;
                }

                Inventory inventory = container.getInventory();
                if (inventory == null || !isValidCombinedInventory(inventory)) {
                    continue;
                }

                String coordinateKey = inventoryCoordinateKey(inventory);
                boolean isNew = coordinateKey != null
                        ? seenCoordinates.add(coordinateKey)
                        : seenIdentities.add(inventory);
                if (isNew) {
                    inventories.add(inventory);
                }
            } catch (RuntimeException ignored) {
                // Une cible obsolète ne doit invalider ni la session ni les autres coffres.
            }
        }
        return inventories;
    }

    private boolean isValidContainer(Container container) {
        try {
            return container != null && containerValidator.test(container);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Refuse un double coffre dès qu'une de ses moitiés n'appartient pas à la
     * session. Sans ce contrôle, un joueur pourrait accoler un coffre privé à
     * un coffre automatique et recevoir une partie des ressources extraites.
     */
    private boolean isValidCombinedInventory(Inventory inventory) {
        InventoryHolder holder;
        try {
            holder = inventory.getHolder();
        } catch (RuntimeException ignored) {
            return false;
        }

        if (!(holder instanceof DoubleChest doubleChest)) {
            return true;
        }
        return isValidContainerSide(doubleChest.getLeftSide())
                && isValidContainerSide(doubleChest.getRightSide());
    }

    private boolean isValidContainerSide(InventoryHolder holder) {
        return holder instanceof Container container && isValidContainer(container);
    }

    /**
     * Construit une identité stable pour les inventaires de blocs.
     *
     * <p>Paper peut fournir deux wrappers Java différents pour les deux moitiés
     * d'un même double coffre. Les deux coordonnées triées représentent alors
     * un seul stockage et empêchent de compter deux fois sa capacité.</p>
     */
    private String inventoryCoordinateKey(Inventory inventory) {
        InventoryHolder holder;
        try {
            holder = inventory.getHolder();
        } catch (RuntimeException ignored) {
            return null;
        }
        return holderCoordinateKey(holder);
    }

    private String holderCoordinateKey(InventoryHolder holder) {
        if (holder instanceof DoubleChest doubleChest) {
            String left = holderCoordinateKey(doubleChest.getLeftSide());
            String right = holderCoordinateKey(doubleChest.getRightSide());
            if (left == null || right == null) {
                return null;
            }
            return left.compareTo(right) <= 0
                    ? "double:" + left + "|" + right
                    : "double:" + right + "|" + left;
        }
        if (holder instanceof Container container) {
            Block block = container.getBlock();
            if (block == null || block.getWorld() == null) {
                return null;
            }
            return "block:" + block.getWorld().getUID()
                    + ":" + block.getX()
                    + ":" + block.getY()
                    + ":" + block.getZ();
        }
        return null;
    }

    private record InventorySnapshot(ItemStack[] contents, int maxStackSize) {
    }
}

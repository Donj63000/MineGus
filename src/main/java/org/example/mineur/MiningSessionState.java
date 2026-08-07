package org.example.mineur;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * État sérialisable d'une session de mineur.
 */
public final class MiningSessionState {

    public static final int CURRENT_SCHEMA_VERSION = 5;
    private static final int MAX_TRUSTED_PLAYERS = 256;
    private static final int MAX_STORED_CONTAINERS = 256;
    private static final int MAX_INSPECTED_LIST_ENTRIES = 1_024;

    public UUID id = UUID.randomUUID();
    public UUID worldUid;
    public Location base;
    public int width;
    public int length;
    public MiningPattern pattern = MiningPattern.QUARRY;
    public MiningSpeed speed = MiningSpeed.NORMAL;
    public MiningCursor cursor;

    /**
     * Checkpoint enregistré entre la sélection et la casse effective d'un bloc.
     * Il permet de rejouer ce bloc après une pause ou un crash.
     */
    public MiningCursor pendingCursor;

    public double minerY;
    public UUID owner;
    public final List<Vector> containers = new ArrayList<>();

    /**
     * Version et emprise du chevalement automatique. Une valeur nulle conserve
     * la compatibilité avec les anciennes sessions qui utilisaient des coffres
     * isolés autour de la mine.
     */
    public int structureVersion = 0;
    public StructureBounds structureBounds;
    public boolean useBarrelMaster = false;
    public boolean paused = false;
    public boolean waitingStorage = false;
    public boolean selected = false;
    public final Set<UUID> trusted = new HashSet<>();

    // Mode « carrière puis tunnel ».
    public boolean chainTunnelAfterQuarry = false;

    // Paramètres du tunnel par tronçons.
    public boolean infiniteTunnel = false;
    public BlockFace tunnelDirection = BlockFace.SOUTH;
    public int tunnelSectionSize = 10;
    public int tunnelHeight = 3;
    public int tunnelSectionsMined = 0;
    public int maxTunnelSections = 0;

    public Map<String, Object> toMap() {
        if (base == null) {
            throw new IllegalStateException("La base de la session mineur est absente.");
        }
        UUID effectiveWorldUid = worldUid;
        if (effectiveWorldUid == null && base.getWorld() != null) {
            effectiveWorldUid = base.getWorld().getUID();
        }
        if (effectiveWorldUid == null) {
            throw new IllegalStateException("Le monde de la session mineur est absent.");
        }

        if (id == null) {
            id = UUID.randomUUID();
        }
        worldUid = effectiveWorldUid;

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", CURRENT_SCHEMA_VERSION);
        map.put("id", id.toString());
        map.put("world", effectiveWorldUid.toString());
        map.put("base", List.of(base.getBlockX(), base.getBlockY(), base.getBlockZ()));
        map.put("width", Math.max(1, width));
        map.put("length", Math.max(1, length));
        map.put("pattern", (pattern != null ? pattern : MiningPattern.QUARRY).name());
        map.put("speed", (speed != null ? speed : MiningSpeed.NORMAL).name());
        map.put("cursor", cursor != null ? cursor.toMap() : null);
        map.put("pendingCursor", pendingCursor != null ? pendingCursor.toMap() : null);
        map.put(
                "minerY",
                Double.isFinite(minerY) ? minerY : base.getBlockY() + 1.0D
        );
        map.put("owner", owner != null ? owner.toString() : "");

        List<Vector> orderedContainers = new ArrayList<>();
        for (Vector vector : containers) {
            if (vector != null) {
                orderedContainers.add(vector);
            }
        }
        orderedContainers.sort((first, second) -> {
            int xOrder = Integer.compare(first.getBlockX(), second.getBlockX());
            if (xOrder != 0) {
                return xOrder;
            }
            int yOrder = Integer.compare(first.getBlockY(), second.getBlockY());
            return yOrder != 0
                    ? yOrder
                    : Integer.compare(first.getBlockZ(), second.getBlockZ());
        });

        List<List<Integer>> serializedContainers = new ArrayList<>();
        Set<String> serializedCoordinates = new HashSet<>();
        for (Vector vector : orderedContainers) {
            if (serializedContainers.size() >= MAX_STORED_CONTAINERS) {
                break;
            }
            String coordinateKey = vector.getBlockX() + ":" + vector.getBlockY()
                    + ":" + vector.getBlockZ();
            if (serializedCoordinates.add(coordinateKey)) {
                serializedContainers.add(List.of(
                        vector.getBlockX(),
                        vector.getBlockY(),
                        vector.getBlockZ()
                ));
            }
        }
        map.put("containers", serializedContainers);

        /*
         * Une version sans emprise ne représente aucune structure restaurable.
         * Sérialiser explicitement zéro évite un état YAML contradictoire.
         */
        map.put(
                "structureVersion",
                structureBounds != null ? Math.max(0, structureVersion) : 0
        );
        map.put(
                "structureBounds",
                structureBounds != null ? structureBounds.toList() : null
        );
        map.put("useBarrelMaster", useBarrelMaster);
        map.put("paused", paused);
        map.put("waitingStorage", waitingStorage);
        map.put("selected", selected);
        map.put("chainTunnelAfterQuarry", chainTunnelAfterQuarry);
        map.put("infiniteTunnel", infiniteTunnel);
        map.put("tunnelDirection", tunnelDirection != null ? tunnelDirection.name() : BlockFace.SOUTH.name());
        map.put("tunnelSectionSize", Math.max(1, tunnelSectionSize));
        map.put("tunnelHeight", Math.max(1, tunnelHeight));
        map.put("tunnelSectionsMined", Math.max(0, tunnelSectionsMined));
        map.put("maxTunnelSections", Math.max(0, maxTunnelSections));

        List<String> trustedList = new ArrayList<>();
        for (UUID uuid : trusted) {
            if (uuid != null) {
                trustedList.add(uuid.toString());
            }
        }
        trustedList.sort(String::compareTo);
        if (trustedList.size() > MAX_TRUSTED_PLAYERS) {
            trustedList = new ArrayList<>(trustedList.subList(0, MAX_TRUSTED_PLAYERS));
        }
        map.put("trusted", trustedList);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static MiningSessionState fromMap(World world, Map<String, Object> map) {
        if (world == null || map == null) {
            return null;
        }

        int schemaVersion = intValue(map.get("schemaVersion"), 1);
        if (schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Version de session mineur non prise en charge : " + schemaVersion + "."
            );
        }

        MiningSessionState state = new MiningSessionState();
        state.id = uuidValue(map.get("id"), UUID.randomUUID());
        /*
         * SessionStore a déjà résolu le monde à partir de l'UUID persistant.
         * Utiliser cet objet comme autorité empêche une carte incohérente de
         * charger des chunks dans un autre monde.
         */
        state.worldUid = world.getUID();
        if (state.worldUid == null) {
            throw new IllegalArgumentException("Le monde de la session ne possède pas d'UUID.");
        }

        if (!(map.get("base") instanceof List<?> baseList)
                || baseList.size() < 3
                || nullableInt(baseList.get(0)) == null
                || nullableInt(baseList.get(1)) == null
                || nullableInt(baseList.get(2)) == null) {
            /*
             * Une base manquante ne doit jamais être remplacée silencieusement
             * par (0, minY, 0), au risque d'activer une mine au mauvais endroit.
             */
            throw new IllegalArgumentException("Base de session absente ou invalide.");
        }

        int minimumY = effectiveMinimumHeight(world);
        int maximumY = effectiveMaximumHeight(world, minimumY);
        int baseX = nullableInt(baseList.get(0));
        int baseY = nullableInt(baseList.get(1));
        int baseZ = nullableInt(baseList.get(2));
        baseY = Math.max(minimumY, Math.min(maximumY - 1, baseY));
        state.base = new Location(world, baseX, baseY, baseZ);

        state.width = Math.max(1, intValue(map.get("width"), 1));
        state.length = Math.max(1, intValue(map.get("length"), 1));
        state.pattern = enumValue(MiningPattern.class, map.get("pattern"), MiningPattern.QUARRY);
        state.speed = enumValue(MiningSpeed.class, map.get("speed"), MiningSpeed.NORMAL);

        Object cursorObj = map.get("cursor");
        if (cursorObj instanceof Map<?, ?> cursorMap) {
            state.cursor = MiningCursor.fromMap((Map<String, Object>) cursorMap);
        }
        Object pendingObj = map.get("pendingCursor");
        if (pendingObj instanceof Map<?, ?> pendingMap) {
            state.pendingCursor = MiningCursor.fromMap((Map<String, Object>) pendingMap);
        }

        state.minerY = doubleValue(map.get("minerY"), baseY);
        state.owner = uuidValue(map.get("owner"), null);

        Object containersObj = map.get("containers");
        if (containersObj instanceof List<?> list) {
            Set<String> seenCoordinates = new HashSet<>();
            int inspected = 0;
            for (Object entry : list) {
                if (inspected++ >= MAX_INSPECTED_LIST_ENTRIES
                        || state.containers.size() >= MAX_STORED_CONTAINERS) {
                    break;
                }
                if (!(entry instanceof List<?> coordinates) || coordinates.size() < 3) {
                    continue;
                }
                Integer x = nullableInt(coordinates.get(0));
                Integer y = nullableInt(coordinates.get(1));
                Integer z = nullableInt(coordinates.get(2));
                if (x != null && y != null && z != null) {
                    String coordinateKey = x + ":" + y + ":" + z;
                    if (seenCoordinates.add(coordinateKey)) {
                        state.containers.add(new Vector(x, y, z));
                    }
                }
            }
        }

        state.structureVersion = Math.max(0, intValue(map.get("structureVersion"), 0));
        state.structureBounds = structureBoundsValue(
                map.get("structureBounds"),
                state,
                minimumY,
                maximumY
        );
        if (state.structureBounds == null) {
            state.structureVersion = 0;
        }

        state.useBarrelMaster = booleanValue(map.get("useBarrelMaster"), false);
        state.paused = booleanValue(map.get("paused"), false);
        state.waitingStorage = booleanValue(map.get("waitingStorage"), false);
        state.selected = booleanValue(map.get("selected"), false);
        state.chainTunnelAfterQuarry = booleanValue(map.get("chainTunnelAfterQuarry"), false);
        state.infiniteTunnel = booleanValue(map.get("infiniteTunnel"), false);

        BlockFace direction = enumValue(BlockFace.class, map.get("tunnelDirection"), BlockFace.SOUTH);
        state.tunnelDirection = isCardinal(direction) ? direction : BlockFace.SOUTH;
        state.tunnelSectionSize = Math.max(1, intValue(map.get("tunnelSectionSize"), 10));
        state.tunnelHeight = Math.max(1, intValue(map.get("tunnelHeight"), 3));
        state.tunnelSectionsMined = Math.max(0, intValue(map.get("tunnelSectionsMined"), 0));
        state.maxTunnelSections = Math.max(0, intValue(map.get("maxTunnelSections"), 0));

        Object trustedObj = map.get("trusted");
        if (trustedObj instanceof List<?> list) {
            int inspected = 0;
            for (Object entry : list) {
                if (inspected++ >= MAX_INSPECTED_LIST_ENTRIES
                        || state.trusted.size() >= MAX_TRUSTED_PLAYERS) {
                    break;
                }
                UUID trustedId = uuidValue(entry, null);
                if (trustedId != null) {
                    state.trusted.add(trustedId);
                }
            }
        }

        if (state.cursor == null) {
            state.cursor = new MiningCursor(state.base, state.width, state.length);
        }
        state.cursor.width = Math.max(1, state.cursor.width);
        state.cursor.length = Math.max(1, state.cursor.length);
        return state;
    }

    /**
     * Restaure le dernier checkpoint non validé puis le consomme.
     */
    public void rollbackPendingCursor() {
        if (pendingCursor == null) {
            return;
        }
        if (cursor == null) {
            cursor = pendingCursor.copy();
        } else {
            cursor.copyFrom(pendingCursor);
        }
        pendingCursor = null;
    }

    /**
     * Emprise bornée et sérialisable du bâtiment associé à la mine.
     */
    public record StructureBounds(int minX,
                                  int maxX,
                                  int minY,
                                  int maxY,
                                  int minZ,
                                  int maxZ) {

        public StructureBounds {
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("Emprise de structure invalide.");
            }
        }

        public List<Integer> toList() {
            return List.of(minX, maxX, minY, maxY, minZ, maxZ);
        }

        public boolean containsHorizontal(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    private static StructureBounds structureBoundsValue(Object value,
                                                        MiningSessionState state,
                                                        int worldMinimumY,
                                                        int worldMaximumY) {
        if (!(value instanceof List<?> list) || list.size() < 6 || state == null || state.base == null) {
            return null;
        }

        Integer minX = nullableInt(list.get(0));
        Integer maxX = nullableInt(list.get(1));
        Integer minY = nullableInt(list.get(2));
        Integer maxY = nullableInt(list.get(3));
        Integer minZ = nullableInt(list.get(4));
        Integer maxZ = nullableInt(list.get(5));
        if (minX == null || maxX == null || minY == null || maxY == null
                || minZ == null || maxZ == null
                || minX > maxX || minY > maxY || minZ > maxZ) {
            return null;
        }

        long spanX = (long) maxX - minX + 1L;
        long spanY = (long) maxY - minY + 1L;
        long spanZ = (long) maxZ - minZ + 1L;
        if (spanX > 1_024L || spanY > 128L || spanZ > 1_024L
                || minY < worldMinimumY || maxY >= worldMaximumY) {
            return null;
        }

        /*
         * Une emprise forgée ne doit pas pouvoir forcer le chargement de chunks
         * éloignés. Le bâtiment réel reste à moins de 128 blocs de l'emprise
         * minée, même avec les limites de configuration maximales.
         */
        long mineMaxX = (long) state.base.getBlockX() + Math.max(1, state.width) - 1L;
        long mineMaxZ = (long) state.base.getBlockZ() + Math.max(1, state.length) - 1L;
        if ((long) state.base.getBlockX() - minX > 128L
                || (long) minX - mineMaxX > 128L
                || (long) maxX - mineMaxX > 128L
                || (long) state.base.getBlockZ() - minZ > 128L
                || (long) minZ - mineMaxZ > 128L
                || (long) maxZ - mineMaxZ > 128L) {
            return null;
        }

        return new StructureBounds(minX, maxX, minY, maxY, minZ, maxZ);
    }

    private static int effectiveMinimumHeight(World world) {
        int minimum = world.getMinHeight();
        int maximum = world.getMaxHeight();
        return maximum > minimum ? minimum : -64;
    }

    private static int effectiveMaximumHeight(World world, int minimum) {
        int maximum = world.getMaxHeight();
        return maximum > minimum ? maximum : 320;
    }

    private static boolean isCardinal(BlockFace face) {
        return face == BlockFace.NORTH
                || face == BlockFace.SOUTH
                || face == BlockFace.EAST
                || face == BlockFace.WEST;
    }

    private static Integer nullableInt(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        double decimal = number.doubleValue();
        if (!Double.isFinite(decimal)) {
            return null;
        }
        if (decimal <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (decimal >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) decimal;
    }

    private static int intValue(Object value, int fallback) {
        Integer result = nullableInt(value);
        return result != null ? result : fallback;
    }

    private static double doubleValue(Object value, double fallback) {
        if (!(value instanceof Number number)) {
            return fallback;
        }
        double result = number.doubleValue();
        return Double.isFinite(result) ? result : fallback;
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        return value instanceof Boolean bool ? bool : fallback;
    }

    private static UUID uuidValue(Object value, UUID fallback) {
        String text = value != null ? value.toString().trim() : "";
        if (text.isEmpty()) {
            return fallback;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, Object value, E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.toString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}

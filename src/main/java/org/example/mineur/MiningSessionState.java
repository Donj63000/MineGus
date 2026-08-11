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

    public static final int CURRENT_SCHEMA_VERSION = 10;
    public static final int CURRENT_TUNNEL_INFRASTRUCTURE_VERSION = 1;
    private static final int MAX_TRUSTED_PLAYERS = 256;
    private static final int MAX_STORED_CONTAINERS = 256;
    private static final int MAX_TUNNEL_FRONTIER_SEALS = 20_000;
    private static final int MAX_TUNNEL_FOREIGN_FRONTIER_SEALS = 20_000;
    private static final int MAX_TUNNEL_PERMANENT_SEALS = 20_000;
    private static final int MAX_PENDING_TUNNEL_FRONTIER_SEALS = 20_000;
    private static final int MAX_PENDING_TUNNEL_PERMANENT_SEALS = 20_000;
    private static final int MAX_INSPECTED_TUNNEL_SEALS = 20_000;
    private static final int MAX_TUNNEL_SECTION_KEY_LENGTH = 256;
    private static final int MAX_INSPECTED_LIST_ENTRIES = 1_024;
    private static final int MAX_TUNNEL_ORIGIN_HORIZONTAL_SPAN = 512;
    private static final int MAX_TUNNEL_ORIGIN_VERTICAL_SPAN = 32;
    private static final int MAX_TUNNEL_ORIGIN_DISTANCE_FROM_MINE = 512;

    /**
     * Origine persistée d'une pause.
     *
     * <p>Les versions antérieures au schéma 6 ne stockaient qu'un booléen.
     * {@link #LEGACY_UNKNOWN} permet de reconnaître ces anciens états et de
     * réparer une fois les pauses erronées créées au démarrage lorsque le
     * propriétaire était hors ligne.</p>
     */
    public enum PauseReason {
        NONE,
        MANUAL,
        PROTECTION,
        ERROR,
        SAFETY,
        LEGACY_UNKNOWN
    }

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
    public PauseReason pauseReason = PauseReason.NONE;
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
    public int tunnelInfrastructureVersion = 0;
    public boolean tunnelArrivalRoom = false;
    /*
     * Je conserve l'emprise compacte du premier tronçon pour pouvoir le
     * reconnaître même lorsque le curseur actif a progressé loin dans la mine.
     */
    public StructureBounds tunnelOriginBounds;
    public String preparedTunnelSection = "";
    public final List<Vector> tunnelFrontierSeals = new ArrayList<>();
    /*
     * Je distingue les verres temporaires déjà présents dans le monde : ils
     * doivent être rouverts avec leurs drops sans devenir notre propriété.
     */
    public final List<Vector> tunnelForeignFrontierSeals = new ArrayList<>();
    /*
     * Je conserve séparément les scellements permanents du tronçon actif afin
     * de ne jamais les confondre avec la couture temporaire de sa face avant.
     */
    public final List<Vector> tunnelPermanentSeals = new ArrayList<>();
    /*
     * Je journalise séparément les poses calculées mais pas encore validées :
     * je peux ainsi reprendre proprement une section après un arrêt brutal.
     */
    public String pendingTunnelInfrastructureSection = "";
    public final List<Vector> pendingTunnelFrontierSeals = new ArrayList<>();
    public final List<Vector> pendingTunnelPermanentSeals = new ArrayList<>();

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
        map.put("pauseReason", normalizedPauseReason().name());
        map.put("waitingStorage", waitingStorage);
        map.put("selected", selected);
        map.put("chainTunnelAfterQuarry", chainTunnelAfterQuarry);
        map.put("infiniteTunnel", infiniteTunnel);
        map.put("tunnelDirection", tunnelDirection != null ? tunnelDirection.name() : BlockFace.SOUTH.name());
        map.put("tunnelSectionSize", Math.max(1, tunnelSectionSize));
        map.put("tunnelHeight", Math.max(1, tunnelHeight));
        map.put("tunnelSectionsMined", Math.max(0, tunnelSectionsMined));
        map.put("maxTunnelSections", Math.max(0, maxTunnelSections));
        map.put("tunnelInfrastructureVersion", Math.max(0, tunnelInfrastructureVersion));
        map.put("tunnelArrivalRoom", tunnelArrivalRoom);
        StructureBounds serializedTunnelOriginBounds = serializableTunnelOriginBounds(
                effectiveWorldUid
        );
        map.put(
                "tunnelOriginBounds",
                serializedTunnelOriginBounds != null
                        ? serializedTunnelOriginBounds.toList()
                        : null
        );
        map.put(
                "preparedTunnelSection",
                preparedTunnelSection != null
                        && preparedTunnelSection.length() <= MAX_TUNNEL_SECTION_KEY_LENGTH
                        ? preparedTunnelSection
                        : ""
        );

        map.put(
                "tunnelFrontierSeals",
                serializeTunnelSeals(tunnelFrontierSeals, MAX_TUNNEL_FRONTIER_SEALS)
        );
        map.put(
                "tunnelForeignFrontierSeals",
                serializeTunnelSeals(
                        tunnelForeignFrontierSeals,
                        MAX_TUNNEL_FOREIGN_FRONTIER_SEALS
                )
        );
        map.put(
                "tunnelPermanentSeals",
                serializeTunnelSeals(tunnelPermanentSeals, MAX_TUNNEL_PERMANENT_SEALS)
        );
        map.put(
                "pendingTunnelInfrastructureSection",
                pendingTunnelInfrastructureSection != null
                        && pendingTunnelInfrastructureSection.length()
                        <= MAX_TUNNEL_SECTION_KEY_LENGTH
                        ? pendingTunnelInfrastructureSection
                        : ""
        );
        map.put(
                "pendingTunnelFrontierSeals",
                serializeTunnelSeals(
                        pendingTunnelFrontierSeals,
                        MAX_PENDING_TUNNEL_FRONTIER_SEALS
                )
        );
        map.put(
                "pendingTunnelPermanentSeals",
                serializeTunnelSeals(
                        pendingTunnelPermanentSeals,
                        MAX_PENDING_TUNNEL_PERMANENT_SEALS
                )
        );

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
        state.pauseReason = deserializePauseReason(map, state.paused, schemaVersion);
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
        state.tunnelInfrastructureVersion = Math.max(
                0,
                intValue(map.get("tunnelInfrastructureVersion"), 0)
        );
        state.tunnelArrivalRoom = booleanValue(map.get("tunnelArrivalRoom"), false);
        state.tunnelOriginBounds = tunnelOriginBoundsValue(
                map.get("tunnelOriginBounds"),
                state,
                minimumY,
                maximumY
        );
        Object preparedSection = map.get("preparedTunnelSection");
        if (preparedSection instanceof String text
                && text.length() <= MAX_TUNNEL_SECTION_KEY_LENGTH) {
            state.preparedTunnelSection = text;
        }

        Object pendingInfrastructureSection = map.get("pendingTunnelInfrastructureSection");
        if (pendingInfrastructureSection instanceof String text
                && text.length() <= MAX_TUNNEL_SECTION_KEY_LENGTH) {
            state.pendingTunnelInfrastructureSection = text;
        }

        deserializeTunnelSeals(
                map.get("tunnelFrontierSeals"),
                state.cursor,
                minimumY,
                maximumY,
                MAX_TUNNEL_FRONTIER_SEALS,
                state.tunnelFrontierSeals
        );
        deserializeTunnelSeals(
                map.get("tunnelForeignFrontierSeals"),
                state.cursor,
                minimumY,
                maximumY,
                MAX_TUNNEL_FOREIGN_FRONTIER_SEALS,
                state.tunnelForeignFrontierSeals
        );
        deserializeTunnelSeals(
                map.get("tunnelPermanentSeals"),
                state.cursor,
                minimumY,
                maximumY,
                MAX_TUNNEL_PERMANENT_SEALS,
                state.tunnelPermanentSeals
        );
        deserializeTunnelSeals(
                map.get("pendingTunnelFrontierSeals"),
                state.cursor,
                minimumY,
                maximumY,
                MAX_PENDING_TUNNEL_FRONTIER_SEALS,
                state.pendingTunnelFrontierSeals
        );
        deserializeTunnelSeals(
                map.get("pendingTunnelPermanentSeals"),
                state.cursor,
                minimumY,
                maximumY,
                MAX_PENDING_TUNNEL_PERMANENT_SEALS,
                state.pendingTunnelPermanentSeals
        );

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
     * Met la session en pause en conservant une cause exploitable après un
     * redémarrage. Une cause absente ou incohérente est traitée comme une
     * erreur interne plutôt que comme une pause manuelle.
     */
    public void pause(PauseReason reason) {
        paused = true;
        pauseReason = reason != null && reason != PauseReason.NONE
                ? reason
                : PauseReason.ERROR;
    }

    /**
     * Réactive la session et efface atomiquement la cause de pause.
     */
    public void resume() {
        paused = false;
        pauseReason = PauseReason.NONE;
    }

    /**
     * Répare une pause provenant d'un ancien schéma qui ne permettait pas de
     * distinguer une commande manuelle du faux blocage « propriétaire hors
     * ligne » observé pendant le chargement.
     *
     * @return {@code true} lorsque la session a été réactivée
     */
    public boolean resumeLegacyUnknownPause() {
        if (!paused || pauseReason != PauseReason.LEGACY_UNKNOWN) {
            return false;
        }
        resume();
        return true;
    }

    private PauseReason normalizedPauseReason() {
        if (!paused) {
            pauseReason = PauseReason.NONE;
            return pauseReason;
        }
        if (pauseReason == null || pauseReason == PauseReason.NONE) {
            pauseReason = PauseReason.ERROR;
        }
        return pauseReason;
    }

    private static PauseReason deserializePauseReason(Map<String, Object> map,
                                                      boolean paused,
                                                      int schemaVersion) {
        if (!paused) {
            return PauseReason.NONE;
        }

        /*
         * Seuls les schémas réellement antérieurs à 6 peuvent être migrés.
         * Une clé absente dans un fichier moderne indique au contraire une
         * corruption : la session reste prudemment en pause.
         */
        if (!map.containsKey("pauseReason")) {
            return schemaVersion < 6
                    ? PauseReason.LEGACY_UNKNOWN
                    : PauseReason.ERROR;
        }

        PauseReason reason = enumValue(
                PauseReason.class,
                map.get("pauseReason"),
                PauseReason.ERROR
        );
        return reason != PauseReason.NONE ? reason : PauseReason.ERROR;
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

    /**
     * Je n'écris l'origine du tunnel que si elle appartient encore au monde
     * de la session et respecte les mêmes bornes que lors de la restauration.
     */
    private StructureBounds serializableTunnelOriginBounds(UUID effectiveWorldUid) {
        if (tunnelOriginBounds == null || base == null || effectiveWorldUid == null) {
            return null;
        }
        World world = base.getWorld();
        if (world == null || !effectiveWorldUid.equals(world.getUID())) {
            return null;
        }
        int minimumY = effectiveMinimumHeight(world);
        int maximumY = effectiveMaximumHeight(world, minimumY);
        return isValidTunnelOriginBounds(
                tunnelOriginBounds,
                this,
                minimumY,
                maximumY
        ) ? tunnelOriginBounds : null;
    }

    /**
     * Je restaure uniquement une liste compacte de six entiers exacts. Les
     * décimaux, débordements et coordonnées supplémentaires sont rejetés.
     */
    private static StructureBounds tunnelOriginBoundsValue(Object value,
                                                            MiningSessionState state,
                                                            int worldMinimumY,
                                                            int worldMaximumY) {
        if (!(value instanceof List<?> list) || list.size() != 6) {
            return null;
        }

        Integer minX = nullableExactInt(list.get(0));
        Integer maxX = nullableExactInt(list.get(1));
        Integer minY = nullableExactInt(list.get(2));
        Integer maxY = nullableExactInt(list.get(3));
        Integer minZ = nullableExactInt(list.get(4));
        Integer maxZ = nullableExactInt(list.get(5));
        if (minX == null || maxX == null || minY == null || maxY == null
                || minZ == null || maxZ == null
                || minX > maxX || minY > maxY || minZ > maxZ) {
            return null;
        }

        StructureBounds bounds = new StructureBounds(minX, maxX, minY, maxY, minZ, maxZ);
        return isValidTunnelOriginBounds(
                bounds,
                state,
                worldMinimumY,
                worldMaximumY
        ) ? bounds : null;
    }

    private static boolean isValidTunnelOriginBounds(StructureBounds bounds,
                                                     MiningSessionState state,
                                                     int worldMinimumY,
                                                     int worldMaximumY) {
        if (bounds == null || state == null || state.base == null) {
            return false;
        }

        long spanX = (long) bounds.maxX() - bounds.minX() + 1L;
        long spanY = (long) bounds.maxY() - bounds.minY() + 1L;
        long spanZ = (long) bounds.maxZ() - bounds.minZ() + 1L;
        if (spanX > MAX_TUNNEL_ORIGIN_HORIZONTAL_SPAN
                || spanY > MAX_TUNNEL_ORIGIN_VERTICAL_SPAN
                || spanZ > MAX_TUNNEL_ORIGIN_HORIZONTAL_SPAN
                || bounds.minY() < worldMinimumY
                || bounds.maxY() >= worldMaximumY) {
            return false;
        }

        /*
         * Le premier tronçon peut mesurer 512 blocs et se trouver juste après
         * une carrière elle-même large de 512 blocs. Je compare donc l'emprise
         * à la mine bornée, pas seulement à son bloc d'origine.
         */
        long mineMinX = state.base.getBlockX();
        long mineMinZ = state.base.getBlockZ();
        long mineWidth = Math.max(
                1L,
                Math.min(MAX_TUNNEL_ORIGIN_HORIZONTAL_SPAN, (long) state.width)
        );
        long mineLength = Math.max(
                1L,
                Math.min(MAX_TUNNEL_ORIGIN_HORIZONTAL_SPAN, (long) state.length)
        );
        long mineMaxX = mineMinX + mineWidth - 1L;
        long mineMaxZ = mineMinZ + mineLength - 1L;
        return bounds.minX() >= mineMinX - MAX_TUNNEL_ORIGIN_DISTANCE_FROM_MINE
                && bounds.maxX() <= mineMaxX + MAX_TUNNEL_ORIGIN_DISTANCE_FROM_MINE
                && bounds.minZ() >= mineMinZ - MAX_TUNNEL_ORIGIN_DISTANCE_FROM_MINE
                && bounds.maxZ() <= mineMaxZ + MAX_TUNNEL_ORIGIN_DISTANCE_FROM_MINE;
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

    /**
     * Je trie et déduplique les scellements avant leur écriture afin de rendre
     * le YAML stable tout en respectant strictement la borne mémoire annoncée.
     */
    private static List<List<Integer>> serializeTunnelSeals(List<Vector> source,
                                                            int maximumSeals) {
        List<Vector> ordered = new ArrayList<>();
        if (source != null) {
            for (Vector vector : source) {
                if (vector != null) {
                    ordered.add(vector);
                }
            }
        }
        ordered.sort((first, second) -> {
            int xOrder = Integer.compare(first.getBlockX(), second.getBlockX());
            if (xOrder != 0) {
                return xOrder;
            }
            int yOrder = Integer.compare(first.getBlockY(), second.getBlockY());
            return yOrder != 0
                    ? yOrder
                    : Integer.compare(first.getBlockZ(), second.getBlockZ());
        });

        List<List<Integer>> serialized = new ArrayList<>();
        Set<String> seenCoordinates = new HashSet<>();
        for (Vector vector : ordered) {
            if (serialized.size() >= maximumSeals) {
                break;
            }
            String coordinateKey = vector.getBlockX() + ":" + vector.getBlockY()
                    + ":" + vector.getBlockZ();
            if (seenCoordinates.add(coordinateKey)) {
                serialized.add(List.of(
                        vector.getBlockX(),
                        vector.getBlockY(),
                        vector.getBlockZ()
                ));
            }
        }
        return serialized;
    }

    /**
     * Je partage la même validation pour les coutures temporaires et les
     * scellements permanents : borne, déduplication et proximité du curseur actif.
     */
    private static void deserializeTunnelSeals(Object value,
                                               MiningCursor cursor,
                                               int minimumY,
                                               int maximumY,
                                               int maximumSeals,
                                               List<Vector> target) {
        if (!(value instanceof List<?> list) || target == null) {
            return;
        }

        Set<String> seenCoordinates = new HashSet<>();
        int inspected = 0;
        for (Object entry : list) {
            if (inspected++ >= MAX_INSPECTED_TUNNEL_SEALS
                    || target.size() >= maximumSeals) {
                break;
            }
            if (!(entry instanceof List<?> coordinates) || coordinates.size() < 3) {
                continue;
            }
            Integer x = nullableInt(coordinates.get(0));
            Integer y = nullableInt(coordinates.get(1));
            Integer z = nullableInt(coordinates.get(2));
            if (x == null || y == null || z == null
                    || y < minimumY || y >= maximumY
                    || !isNearActiveTunnelCursor(cursor, x, y, z)) {
                continue;
            }
            String coordinateKey = x + ":" + y + ":" + z;
            if (seenCoordinates.add(coordinateKey)) {
                target.add(new Vector(x, y, z));
            }
        }
    }

    private static boolean isNearActiveTunnelCursor(MiningCursor cursor, int x, int y, int z) {
        if (cursor == null) {
            return false;
        }
        long maxX = (long) cursor.minX + Math.max(1, cursor.width) - 1L;
        long maxY = (long) cursor.minY + Math.max(1, cursor.height) - 1L;
        long maxZ = (long) cursor.minZ + Math.max(1, cursor.length) - 1L;
        return (long) x >= (long) cursor.minX - 1L
                && (long) x <= maxX + 1L
                && (long) y >= (long) cursor.minY - 1L
                && (long) y <= maxY + 1L
                && (long) z >= (long) cursor.minZ - 1L
                && (long) z <= maxZ + 1L;
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

    private static Integer nullableExactInt(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        double decimal = number.doubleValue();
        if (!Double.isFinite(decimal)
                || decimal < Integer.MIN_VALUE
                || decimal > Integer.MAX_VALUE
                || decimal != Math.rint(decimal)) {
            return null;
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

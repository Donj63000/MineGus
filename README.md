# MinePlugin

Plugin Paper pour Minecraft 1.21.4 qui automatise diverses tâches (minage, agriculture, forêt, élevage, etc.). Projet en bêta, expérimental, pensé pour de petits serveurs entre amis.

## Prérequis
- Java 21 (OpenJDK/Temurin). Vérifier: `java -version`
- Maven 3.9+ (ou 3.x). Vérifier: `mvn -v`
- Serveur Paper 1.21.4
- IDE recommandé: IntelliJ IDEA, encodage projet en UTF‑8

Notes:
- Assurez‑vous que `JAVA_HOME` pointe vers une JDK 21 et que `java`/`mvn` sont dans le `PATH`.
- Mémoire serveur conseillée ≥ 2 Go pour les tests locaux.

## Compilation du plugin
1. Clonez le dépôt et placez‑vous à la racine du projet.
2. Exécutez `mvn -q package`.
   - Le JAR est généré dans `target/MineGus-<version>.jar`.

## Installation du plugin (.jar)
1. Récupérez le JAR généré (ex. `MineGus-1.1-SNAPSHOT.jar`).
2. Copiez‑le dans le dossier `plugins/` de votre serveur Paper.
3. Démarrez ou redémarrez le serveur.
4. Vérifiez `/plugins`. Au premier lancement, `plugins/MinePlugin/` est créé pour les données et la configuration.

## Configuration
Après le premier démarrage, éditez `plugins/MinePlugin/config.yml` :
- limites d’animaux et temps de recharge pour `/eleveur` ;
- prix en émeraudes des piles vendues ;
- chance d’obtenir de la viande cuite ;
- taille des maisons/grille du village pour `/village` ;
- marge par défaut autour de la muraille.

La section `village` expose notamment `houseSmall`, `houseBig`, `roadHalf`, `spacing`, `plazaSize`, `rows`, `cols`, `wallGap`. Le fichier de référence est `src/main/resources/config.yml` (ne modifiez pas celui‑ci, utilisez la copie dans `plugins/MinePlugin/`).

## Mode en jeu
1. Connectez‑vous (opérateur) et tapez `/ping` pour vérifier le chargement.
2. Utilisez les commandes ci‑dessous pour créer vos premières zones.
3. Pour arrêter une zone (mine, champ, forêt, ranch), retirez tous les coffres associés.
4. Les données sont sauvegardées dans `plugins/MinePlugin/` et restaurées au redémarrage.

## Commandes disponibles
| Commande    | Description rapide                                                |
|-------------|-------------------------------------------------------------------|
| `/ping`     | Vérifie que le plugin répond (renvoie « Pong! »).                 |
| `/army`     | Invoque temporairement 5 loups et 2 golems protecteurs.           |
| `/mineur`   | Lance un mineur automatique dans une zone sélectionnée.           |
| `/champ`    | Crée un champ agricole automatisé.                                |
| `/foret`    | Met en place une forêt avec récolte et replantation automatiques. |
| `/village`  | Génère un petit village complet près du joueur.                   |
| `/eleveur`  | Construit un enclos d’élevage automatisé.                         |
| `/armure`   | Donne l’armure légendaire du roi GIDON.                           |

## Détails des commandes (aperçu)
- `/mineur`, `/champ`, `/foret`, `/eleveur` : utilisez le bâton « Sélecteur » (clic gauche/droit) pour définir deux coins au même niveau, des PNJ et coffres sont générés, et l’activité est automatisée.
- `/army` : protections temporaires (loups + golems) pendant quelques minutes.

## Persistance des données
Les fonctionnalités sauvegardent leurs informations (YAML) dans `plugins/MinePlugin/` (`sessions.yml`, `farms.yml`, etc.) pour restaurer PNJ et structures au redémarrage.

## Structure du projet
- `src/main/java/` : code source du plugin.
- `src/main/resources/` : `plugin.yml`, `config.yml` par défaut.
- `docs/IDEES.md` : idées et notes.
- `target/` : artefacts de build (ne pas modifier/committer).

## Architecture du projet (qui fait quoi)
- `org.example.MinePlugin` : point d’entrée. Enregistre `/army` et `/ping`, instancie les modules (`Mineur`, `Agriculture`, `Foret`, `Village`, `Eleveur`, `Armure`), charge/sauve les sessions dans `onEnable/onDisable`.
- `org.example.Mineur` : gère `/mineur`. Bâton de sélection (2 blocs même Y), cadre + coffres + PNJ mineur + golems, minage vertical, persistance `sessions.yml`, arrêt si tous les coffres cassés.
- `org.example.Agriculture` : gère `/champ`. Crée terres labourées + irrigation, PNJ fermier + golems, récolte/stockage auto, persistance `farms.yml`.
- `org.example.Foret` : gère `/foret`. Grille de pousses, récolte et replantation auto, coffres d’angle, persistance `forests.yml`.
- `org.example.Eleveur` : gère `/eleveur`. Génère un ranch (clôtures, coffres, spawners), limite d’animaux par espèce (depuis `config.yml`), ramassage et stockage, scoreboard, persistance `ranches.yml`.
- `org.example.Village` : gère `/village` et `undo`. Orchestration asynchrone via une file de `Runnable` pour routes, place, lots, bâtiments, spawners PNJ/golems et muraille; lit la config (`rows`, `cols`, `houseSmall`, `houseBig`, `roadHalf`, `spacing`, `plazaSize`).
- `org.example.Batiments` : helpers statiques de bâtiments (maisons pivotées, toitures, détails). Écrit via `Village#setBlockTracked` pour permettre l’undo.
- `org.example.Golem` : sentinelle golem avec rayon de patrouille, retour au point d’ancrage (pathfinder/teleport), attributs ajustés.
- `org.example.Armure` : gère `/armure`. Donne l’équipement “roi GIDON”, applique/retire les buffs en boucle, invoque des loups gardes à la prise de dégâts et programme leur disparition.
- `org.example.TeleportUtils` : `safeTeleport` (utilise `teleportAsync` si dispo, sinon `teleport`).
- `org.example.village.Disposition` : répartition des lots et planification des tâches (routes, maisons, fermes, lampadaires).
- `org.example.village.HouseBuilder` : génère maisons/fermes/lampadaires; renvoie des actions différées.
- `org.example.village.TerrainManager` : aplanit/remblaie le terrain; utilitaire `SetBlock` safe.
- `org.example.village.WallBuilder` : construit la muraille (corps, crénelages, portes, torches) en tâches différées.
- `org.example.village.VillageEntityManager` : spawn/cleanup des PNJ et golems, taggage par village, limitation de population périodique.

## Développement
- Construire : `mvn -q package` (JAR dans `target/`). Tests optionnels : `mvn -q test` (JUnit 5 sous `src/test/java/`).
- Lancer localement : copier le JAR dans `plugins/` d’un serveur Paper 1.21.4 puis démarrer le serveur.
- Style : Java 21, 4 espaces, accolades sur la même ligne, classes `PascalCase`, méthodes/champs `camelCase`, constantes `UPPER_SNAKE_CASE`.
- Commits : français, présent, concis (ex. « Ajoute collecte du champ »). Ne modifiez jamais `target/*.jar`.
- Voir `AGENTS.md` pour des consignes plus détaillées.

## Guide développeur (amélioré)

### Développement rapide
- Cloner, utiliser Java 21, puis `mvn -q package` → JAR dans `target/`.
- Tester localement: copier le JAR dans `plugins/` d’un Paper 1.21.4 et démarrer.

### Workflow de contribution
- Créer une branche: `feat/...`, `fix/...`, ou `chore/...`.
- Commits en français, au présent, concis (ex.: « Corrige spawn des golems », « Ajoute collecte du champ »).
- Après chaque changement, exécuter `mvn -q package`. Ne pas modifier `target/*.jar`.
- Ouvrir une PR avec: objectif, changements clés, résultat du build, étapes de test manuel (commandes essayées), captures si besoin.

### Ajouter une commande (aperçu)
- Déclarer la commande dans `src/main/resources/plugin.yml` (nom, description, permission si nécessaire).
- Créer une classe d’exécuteur (CommandExecutor/TabExecutor) dans `src/main/java/` selon la structure existante.
- Enregistrer l’exécuteur dans `onEnable()` ou via l’injection utilisée par le projet.
- Mettre à jour la documentation des commandes dans le README.

### Bonnes pratiques serveur
- Éviter les opérations lourdes sur le thread principal; utiliser tâches planifiées/asynchrones.
- Respecter la sécurité des mondes (chunks chargés, pas de force‑load inutile).
- Ne pas utiliser `/reload`; préférer arrêter/redémarrer pour recharger le plugin proprement.

Voir `AGENTS.md` pour plus de détails (structure, style, consignes de sécurité).

## Licence
MIT — voir `LICENSE`.

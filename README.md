# MinePlugin

Plugin Paper pour Minecraft 1.21.4 qui automatise diverses tâches (minage, agriculture, forêt, élevage, etc.). Projet en bêta, expérimental, pensé pour de petits serveurs entre amis.

## Prérequis
- Java 17 (OpenJDK/Temurin). Vérifier: `java -version`
- Maven 3.9+ (ou 3.x). Vérifier: `mvn -v`
- Serveur Paper 1.21.4
- IDE recommandé: IntelliJ IDEA, encodage projet en UTF‑8

Notes:
- Assurez‑vous que `JAVA_HOME` pointe vers une JDK 17 et que `java`/`mvn` sont dans le `PATH`.
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
- `/mineur` : `stop-at-y` (Y cible), `default.pattern`, `default.speed`, `default.supports-every`, `default.torch-layers`, `default.use-barrel-master`, `branch.spacing`, `branch.gallery-width`, `limits.max-sessions-per-player`, `economy.*`.
- `/eleveur` : limites d’animaux, temps de recharge, chances de loot cuit, prix en émeraudes.
- `/village` : tailles des maisons/grille (`houseSmall`, `houseBig`, `roadHalf`, `spacing`, `plazaSize`, `rows`, `cols`, `wallGap`).

Référence par défaut : `src/main/resources/config.yml`. Modifiez seulement la copie dans `plugins/MinePlugin/`.

## Mode en jeu
1. Connectez‑vous (opérateur) et tapez `/ping` pour vérifier le chargement.
2. Utilisez les commandes ci‑dessous pour créer vos premières zones.
3. Pour arrêter une zone (mine, champ, forêt, ranch), retirez tous les coffres associés.
4. Les données sont sauvegardées dans `plugins/MinePlugin/` et restaurées au redémarrage.

## COMMANDES EN JEU
### /ping
- Test de présence du plugin : répond « Pong! ». Disponible pour tous les joueurs et la console.

### /army
- Invoque 5 loups apprivoisés et 2 golems nommés autour du joueur, protégés pendant 5 minutes avant disparition automatique.

### /mineur (permission `mineplugin.mineur.use`)
- `/mineur` : donne le bâton « Sélecteur de mine ». Clique deux blocs au même Y pour créer automatiquement la mine (cadre, coffres, PNJ mineur, golems). Par défaut, le pattern `QUARRY` creuse jusqu’à `stop-at-y` puis enchaîne sur un tunnel infini 10×10.
- `/mineur vitesse <lent|normal|rapide>` : change la cadence du mineur en cours.
- `/mineur pattern <carriere|branche|tunnel|veine>` : change le pattern (veine utilise actuellement carrière). Désactive le chaînage automatique.
- `/mineur pause` / `/mineur reprendre` : met en pause ou relance la session.
- `/mineur stop` : arrête et nettoie la session.
- `/mineur info` : affiche monde, zone, vitesse, pattern, Y courant et conteneurs.
- `/mineur autoriser <joueur>` : autorise un autre joueur à interagir avec la session. Si tous les coffres sont détruits, la mine s’arrête.

### /champ
- Donne le bâton « Sélecteur de champ ». Sélectionne deux blocs au même niveau pour générer une ferme labourée avec irrigation, coffres, PNJ fermier et golems. Récolte et stockage automatiques tant qu’il reste des coffres.

### /foret
- Donne le bâton « Sélecteur de forêt ». Deux blocs au même Y définissent la micro‑forêt : cadre, saplings en grille, PNJ forestier, golems et coffres. Coupe, capture des drops et replantation automatiques, persistance en `forests.yml`. La session s’arrête si tous les coffres sont retirés.

### /eleveur
- `/eleveur` : donne le bâton « Sélecteur d’élevage » pour tracer un ranch automatisé (spawners d’animaux configurés, PNJ éleveur ramasseur, coffres, golems).
- `/eleveur list` : liste les enclos actifs avec leur index.
- `/eleveur delete <id>` : supprime l’enclos correspondant et nettoie la zone.

### /village
- `/village` : génère un village orthogonal asynchrone autour du joueur (routes, place centrale, lots de maisons/fermes, spawners PNJ/golems, muraille, stand marchand).
- `/village undo` : annule la dernière génération effectuée.

### /armure
- Donne l’Armure du roi GIDON (netherite enchantée). En portant l’ensemble : effets permanents (vision nocturne, résistance au feu, respiration, santé/force), et 4 loups gardes apparaissent quand le joueur est touché (disparition programmée).

### /marchand (permission `mineplugin.marchand.spawn`)
- Invoque un PNJ marchand invulnérable à l’emplacement du joueur. Les offres proviennent de `plugins/MinePlugin/marchand.yaml`; clique le PNJ pour ouvrir l’interface d’échanges.

### /job
- `/job mineur` : choisit le métier de mineur.
- `/job` ou `/job info` : affiche métier, niveau, XP et nombre de mines débloquées. L’XP se gagne en minant à la pioche; +1 slot de mine tous les 10 niveaux (jusqu’à 10).

### /minegus (permission `mineplugin.admin`)
- `/minegus fix forestier|golems` : commande de maintenance qui supprime les doublons de forestiers de /foret ou de golems gardes taggés dans tous les mondes.

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
- Style : Java 17, 4 espaces, accolades sur la même ligne, classes `PascalCase`, méthodes/champs `camelCase`, constantes `UPPER_SNAKE_CASE`.
- Commits : français, présent, concis (ex. « Ajoute collecte du champ »). Ne modifiez jamais `target/*.jar`.
- Voir `AGENTS.md` pour des consignes plus détaillées.

## Guide développeur (amélioré)

### Développement rapide
- Cloner, utiliser Java 17, puis `mvn -q package` → JAR dans `target/`.
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

## Démarrage rapide en local (Paper)
1. Téléchargez Paper 1.21.4 depuis https://papermc.io/downloads.
2. Placez `paper-1.21.4.jar` dans un dossier dédié (ex. `server/`).
3. Copiez le JAR du plugin dans `server/plugins/`.
4. Lancez le serveur une première fois (`java -jar paper-1.21.4.jar --nogui`) pour générer les dossiers.
5. Acceptez l’EULA en éditant `eula.txt` (`eula=true`), relancez.
6. Vérifiez que `plugins/MinePlugin/` est créé. Ajustez `config.yml` et `marchand.yaml` dans ce dossier (pas ceux du `src/`).
7. Connectez‑vous en jeu, exécutez `/ping` puis `/army` pour valider le chargement de base.

## Marchand : fiche rapide
- Commandes :
  - `/marchand` (permission `mineplugin.marchand.spawn`) : spawn du PNJ marchand invulnérable.
  - `/marchand open [joueur]` (permission `mineplugin.marchand.open`) : ouvre directement le menu sans PNJ.
  - `/marchand reload` (permission `mineplugin.marchand.admin`) : recharge `marchand.yaml` et réapplique les PNJ existants.
- Interaction : clic droit sur le PNJ pour ouvrir les catégories, navigation par menus 6×9 avec pagination.
- Règles clés lues dans `plugins/MinePlugin/marchand.yaml` :
  - `rules.allow_outputs_as_inputs` : si `false`, les items vendus ne peuvent pas être réutilisés comme entrée (anti‑boucle).
  - `rules.default_caps.per_player_per_day` / `per_server_per_day` : limites par défaut des offres (0 = illimité).
  - `rules.sound_on_trade` et `rules.log_trades` : son et logs console pour chaque échange.
  - `merchant.reset_time_utc` : heure de reset quotidien des compteurs de caps (UTC).
- Caps par offre : chaque entrée peut surcharger via `caps: { per_player_per_day: N, per_server_per_day: M }`.
- Exemple d’offre avec cap serveur :
```yaml
rules:
  default_caps:
    per_player_per_day: 3
    per_server_per_day: 0
categories:
  SPECIAUX_ADMIN:
    icon: TRIAL_SPAWNER
    offers:
      - out: TRIAL_SPAWNER
        in: NETHERITE_BLOCK
        in_qty: 8
        caps: { per_server_per_day: 1 }
        note: "Limité à 1 par jour pour tout le serveur"
```

## Scénarios de test recommandés
- Sanity de base : `/ping`, `/army` (spawn + disparition programmée).
- Mineur : `/mineur`, sélection de deux blocs au même Y, vérifier cadre + coffres + PNJ; changer `/mineur vitesse rapide`.
- Champ : `/champ`, récolte/stockage, suppression des coffres → arrêt automatique.
- Forêt : `/foret`, coupe/replantation automatique, persistance après redémarrage.
- Éleveur : `/eleveur`, `/eleveur list`, `/eleveur delete <id>`; vérifier limites d’animaux configurées.
- Village : `/village`, observer génération progressive, `/village undo` pour rollback.
- Marchand : spawn avec `/marchand`, ouverture via `/marchand open <joueur>`, tester une offre avec cap quotidien et vérifier le message de blocage après dépassement.

## Dépannage rapide
- Le plugin ne se charge pas : vérifier la version Paper (1.21.4), `java -version` (17) et les logs `logs/latest.log`.
- Commande inconnue : confirmer la permission (`op` par défaut sur la plupart des commandes) et que `plugin.yml` est bien dans le JAR généré.
- Menus vides du marchand : valider la syntaxe de `plugins/MinePlugin/marchand.yaml` (indentation, clés `categories:` et `offers:`).
- Caps du marchand qui ne se reset pas : vérifier `merchant.reset_time_utc` (UTC) et attendre la minute suivante après l’heure cible.
- Golems/PNJ en double : utiliser `/minegus fix forestier` ou `/minegus fix golems`.
- Performances : limiter la taille des zones (`/mineur`, `/foret`, `/champ`) et éviter le force‑load de chunks.

## Intégration CI/CD suggérée
- Étape 1 : `mvn -q -B package` (cache Maven si possible).
- Étape 2 : publier `target/MineGus-<version>.jar` comme artefact.
- Étape 3 (optionnel) : lancer un serveur Paper éphémère avec le JAR et exécuter une courte suite de commandes RCON (`/ping`, `/army`, `/marchand open @p`) pour smoke tests.

## Licence
MIT — voir `LICENSE`.

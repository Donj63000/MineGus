# MinePlugin

Plugin Paper pour Minecraft 1.21.4 qui automatise diverses tâches (minage, agriculture, forêt, élevage, etc.). Projet en bêta, expérimental, pensé pour de petits serveurs entre amis.

## Prérequis
- Java 21 (OpenJDK/Temurin). Vérifier : `java -version`
- Maven 3.9+ (ou 3.x). Vérifier : `mvn -v`
- Serveur Paper 1.21.4
- IDE recommandé : IntelliJ IDEA, encodage projet en UTF‑8

Notes :
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
- `/mineur` : `stop-at-y` (Y cible), `default.pattern`, `default.speed`, `default.supports-every`, `default.torch-layers`, `default.use-barrel-master`, `branch.*`, `tunnel.section-size`, `tunnel.height`, `tunnel.max-distance-from-base`, `tunnel.infrastructure.*`, `tunnel.arrival-room.*`, `limits.*`, `economy.*`.
- `/garde` : `follow-radius`, les paramètres de formation (`formation-side-distance`, `formation-rear-distance`, `formation-tolerance`), l’espace visuel (`personal-space-radius`, `view-clearance-distance`), les rayons `protection-radius`, `threat-detection-radius` et `combat-leash-radius`, l’assistance (`assist-owner-attacks`, `proactive-defense`, `finish-engaged-targets`), `threat-duration-seconds`, `respawn-delay-seconds`, `friendly-fire`, la neutralité des golems, `notifications` et `attributes.*`. Les valeurs invalides ou hors limites reviennent aux valeurs par défaut sûres.
- `/eleveur` : limites d’animaux, temps de recharge, chances de loot cuit, prix en émeraudes.
- `/village` : tailles des maisons/grille (`houseSmall`, `houseBig`, `roadHalf`, `spacing`, `plazaSize`, `rows`, `cols`, `wallGap`), densité de végétation (`decorDensity`), passe lumineuse finale (`lighting.*`), garnison (`knights.*`) et chambre forte fiscale (`treasury.*`).
- Progression globale : `progression.xp-levelup`, `progression.bonuses`, `progression.display`, les garde-fous `progression.anti-abuse` et chaque source d'XP sous `progression.sources`. Ce niveau est indépendant du métier Mineur.

Référence par défaut : `src/main/resources/config.yml`. Modifiez seulement la copie dans `plugins/MinePlugin/`.

## Mode en jeu
1. Connectez‑vous (opérateur) et tapez `/ping` pour vérifier le chargement.
2. Utilisez les commandes ci‑dessous pour créer vos premières zones.
3. Pour arrêter une zone (mine, champ, forêt, ranch), retirez tous les coffres associés.
4. Les données des fonctionnalités persistantes sont sauvegardées dans `plugins/MinePlugin/` et restaurées au redémarrage ; les gardes de `/garde` restent volontairement temporaires.

## COMMANDES EN JEU
### /ping
- Test de présence du plugin : répond « Pong! ». Disponible pour tous les joueurs et la console.

### /army (permission `mineplugin.army.use`, opérateur par défaut)
- Invoque 5 loups apprivoisés et 2 golems nommés autour du joueur, protégés pendant 5 minutes avant disparition automatique.
- Une nouvelle invocation remplace uniquement l'armée précédente du même joueur ; elle ne supprime jamais celle des autres joueurs.

### /garde (permission `mineplugin.garde.use`, accordée par défaut)
- `/garde` reste une commande bascule. Les variantes explicites `/garde invoquer`, `/garde renvoyer`, `/garde statut` et `/garde aide` évitent toutefois un renvoi accidentel et donnent l’état des respawns en attente.
- Les gardes sont des Husks vanilla adultes nommés exactement `Garde Royale` ; ce ne sont pas des PNJ joueurs Steve, ce qui demanderait une dépendance externe.
- Limite volontaire du modèle d’entité : Minecraft continue donc de les classer comme Zombies, monstres et morts-vivants. Les réactions vanilla liées à cette nature — notamment la peur des villageois, Châtiment et l’inversion Soins/Dégâts instantanés — restent applicables. Les neutraliser totalement imposerait de remplacer le Husk par un véritable PNJ ou d’utiliser du NMS instable.
- Chaque garde porte une armure complète en netherite (Protection IV, Solidité III, Raccommodage) et une épée en netherite (Tranchant V, Solidité III, Raccommodage). Ces objets sont inaltérables afin qu’un long combat ne désarme jamais silencieusement le garde ; leur équipement et leur expérience ne sont jamais lâchés à la mort.
- Les comportements de Zombie incompatibles avec un garde sont neutralisés : la chance et les modificateurs de renfort sont forcés à zéro, la conversion aquatique est arrêtée, les véhicules sont quittés automatiquement et `prevent-block-damage: true` empêche les œufs de tortue, la terre labourée ou une porte d’être dégradés. Les portes restent ouvrables et les plaques de pression ne sont pas désactivées.
- Ils ont 100 PV, 16 dégâts d’attaque, une vitesse de 0,35, une portée de poursuite de 48 blocs et une résistance au recul de 0,6 par défaut. Ces valeurs sont configurables dans `garde.attributes`. La portée effective est automatiquement relevée au moins à la laisse de combat, afin que l’IA puisse réellement rejoindre toute cible encore autorisée.
- Hors combat, ils occupent deux positions distinctes sur les côtés arrière du joueur au lieu de viser sa position exacte. Une zone personnelle et un cône de vue restent dégagés ; un garde placé devant la caméra reçoit d’abord un point de dégagement latéral. Les gardes ne sont pas collisionnables et un chevauchement presque exact déclenche un rappel sûr, y compris au milieu d’un combat. Les emplacements de rappel refusent aussi de superposer les deux gardes. Le pathfinder est configuré pour flotter et franchir les portes ouvertes ou fermées. Une configuration de formation trop serrée est automatiquement éloignée de la zone personnelle, et la portée de rappel effective est relevée si nécessaire pour contenir la formation. Un changement de monde, un blocage répété ou un véritable dépassement de cette portée provoque le rappel sans dégâts de chute.
- Avec `assist-owner-attacks: true`, toute entité vivante valablement frappée par le propriétaire — au corps à corps, avec une attaque de balayage ou par projectile attribué au joueur — entre dans une file bornée de 24 cibles. Les coups portés à une partie de l’Ender Dragon sont redirigés vers son parent vivant. Une nouvelle cible explicitement frappée remplace immédiatement une autre consigne offensive de même priorité, sans effacer celle-ci de la file. Avec `finish-engaged-targets: true`, le duo conserve la cible active jusqu’à sa mort, son invalidation, son changement de monde ou sa sortie de `combat-leash-radius`, puis reprend automatiquement la suivante.
- Avec `proactive-defense: true`, `EntityTargetLivingEntityEvent` permet d’intercepter un mob dès qu’il choisit le propriétaire ou l’un de ses gardes comme cible hostile. Un balayage local borné par `threat-detection-radius` et plafonné à 64 blocs, une fois par seconde, récupère aussi les cibles injectées sans événement. Les raisons non agressives comme `TEMPT` sont ignorées et les animaux apprivoisés appartenant au propriétaire sont toujours protégés. Une menace visant réellement le maître ou l’un des gardes passe avant une simple consigne offensive, mais la consigne est conservée pour être reprise après le danger. Un joueur ne publie pas d’état de cible comparable à celui d’un mob : un joueur hostile est donc engagé dès son premier dégât valide, ou dès que le propriétaire le frappe.
- Lorsqu’un joueur protégé ou l’un de ses gardes est blessé par des dégâts non annulés, les deux gardes ciblent le véritable agresseur, y compris pour les dégâts indirects attribués au tireur. Les coups accidentels du propriétaire sont bloqués par défaut. Tous les golems de fer — naturels, gardes de village, créés par MineGus ou par un joueur — sont neutralisés par défaut (`iron-golem-neutrality: true`) : acquisition de cible annulée, cible résiduelle purgée au tick suivant, contrôle local borné et dégâts annulés. Si un golem parvient malgré tout à tenter une frappe, `iron-golem-retaliation: true` ordonne au duo de contre-attaquer sans exposer le garde aux dégâts. Le duo peut toujours défendre son propriétaire contre un golem. Pour autoriser entièrement les combats vanilla garde contre golem, mettre `iron-golem-neutrality: false`. Les golems de neige bénéficient de la même neutralité avec `snow-golem-neutrality: true`, mais leurs projectiles résiduels ne déclenchent volontairement aucune contre-attaque afin de ne pas détruire une défense amicale de la base.
- Avec `allow-sleep-near-guards: true`, les gardes ne bloquent plus le lit lorsqu’ils sont les seuls monstres dans la zone de sécurité vanilla. Le plugin conserve le refus si un véritable ennemi est présent, si le garde d’un autre joueur menace le dormeur ou si un autre plugin a explicitement interdit le lit.
- La commande refuse explicitement l’invocation dans un monde en difficulté Paisible et renvoie un duo actif si le monde y passe : Minecraft supprime les Husks dans ce mode et provoquerait sinon des disparitions/réapparitions en boucle.
- Lorsqu’un garde meurt, il réapparaît après `garde.respawn-delay-seconds` (20 secondes par défaut), seulement si son duo est toujours actif et que le propriétaire est connecté. Un échec temporaire d’apparition est automatiquement reprogrammé. Les gardes sont supprimés à la déconnexion, au renvoi et à l’arrêt du plugin ; ils ne persistent pas après un redémarrage.

### /tuto (permission `mineplugin.tuto.use`)
- `/tuto` lance une visite guidée privée dans un monde temporaire généré pour le joueur : grande plaine stable, falaises à l'horizon, décors progressifs et DonjBot animé.
- DonjBot présente puis simule visuellement `/village`, `/mineur`, `/champ`, `/foret`, `/eleveur`, `/garde`, `/armure`, `/army`, `/marchand`, `/job`, `/niveau`, `/minegus` et `/ping`. Les vraies commandes ne sont jamais appelées dans le bac à sable : aucune session métier, structure persistante ou XP n'est créée.
- DonjBot marche réellement entre les stations. Si le joueur dépasse la distance configurée (15 blocs par défaut), il est rappelé près du guide.
- Relancer `/tuto` pendant la préparation l'annule ; relancer `/tuto` pendant la visite la quitte et restaure exactement le monde, la position, l'orientation, le mode de jeu, le vol et l'état temporairement modifié.
- La destination de retour est écrite atomiquement dans `tutorial-returns.yml` avant la première téléportation. Une reconnexion ou un redémarrage après incident effectue automatiquement le retour ; si le monde d'origine est encore déchargé, l'entrée est conservée et reprise dès son chargement. Les mondes marqués comme temporaires sont ensuite déchargés et supprimés sans suivre de lien symbolique.
- Réglages : `tutorial.enabled`, limite de sessions, distance de rappel, vitesse de DonjBot, budgets de construction, délai de chargement, retour automatique, préfixe/borne/heure/graine du monde.

### /mineur (permission `mineplugin.mineur.use`)
- `/mineur` : donne le bâton « Sélecteur de mine ». Clique deux blocs au même Y pour créer automatiquement la mine (cadre, coffres, PNJ mineur, golems). Par défaut, le pattern `QUARRY` creuse jusqu’à `stop-at-y` puis enchaîne sur un tunnel de 10 blocs de large sur 3 blocs de haut, avancé par sections de 10 blocs et borné à 2 048 blocs de la base.
- Lors du premier passage de la carrière au tunnel, une salle d’arrivée de 15×15×5 est aménagée avec plateformes, coffres et postes de travail. Chaque section reçoit ensuite des pieds de soutien, des poutres en bois espacées de 5 blocs et des lumières intégrées. Le drainage reste borné au volume actif ; le verre bleu clair ferme les contacts fluides et les ouvertures de grotte exposées.
- `/mineur vitesse <lent|normal|rapide>` : change la cadence du mineur en cours.
- `/mineur pattern <carriere|branche|tunnel|veine>` : change le pattern (veine utilise actuellement carrière). Désactive le chaînage automatique.
- `/mineur pause` / `/mineur reprendre` : met en pause ou relance la session.
- `/mineur stop` : arrête et nettoie la session.
- `/mineur info` : affiche monde, zone, vitesse, pattern, Y courant et conteneurs.
- `/mineur autoriser <joueur>` : autorise un autre joueur à interagir avec la session. Si tous les coffres sont détruits, la mine s’arrête.

### /champ (permission `mineplugin.champ.use`, opérateur par défaut)
- Donne le bâton « Sélecteur de champ ». Sélectionne deux blocs au même niveau pour générer une ferme labourée avec irrigation, coffres, PNJ fermier et golems. Récolte et stockage automatiques tant qu’il reste des coffres.
- Dimensions admises : de 3×3 à 64×64, avec une surface maximale de 4 096 blocs. Un joueur peut posséder au plus 4 champs (hors administrateur), dans la limite globale de 64. Sur les champs étroits, le nombre de coffres par coin est réduit automatiquement pour éviter tout chevauchement. Seul le propriétaire, ou un administrateur avec `mineplugin.champ.admin`, peut modifier la structure gérée.

### /foret (permission `minegus.forester.use`)
- Donne le bâton « Sélecteur de forêt ». Deux blocs au même Y définissent la micro‑forêt : cadre, saplings en grille, PNJ forestier, golems et coffres. Coupe, capture des drops et replantation automatiques, persistance en `forests.yml`. La session s’arrête si tous les coffres sont retirés.
- Dimensions admises : de 4×4 à 64×64, avec une surface maximale de 4 096 blocs. `/foret supprimer <numéro>` désactive l'automatisation et retire ses entités gérées, mais conserve la structure de blocs. La permission `minegus.forester.bypass` permet la maintenance manuelle d'une parcelle protégée.

### /eleveur (permission `mineplugin.eleveur.use`, opérateur par défaut)
- `/eleveur` : donne le bâton « Sélecteur d’élevage » pour tracer un ranch automatisé (spawners d’animaux configurés, PNJ éleveur ramasseur, coffres, golems).
- `/eleveur list` : liste uniquement les enclos que le joueur peut administrer, avec un index commençant à 1.
- `/eleveur delete <numéro>` : désactive l’automatisation de l’enclos et retire ses entités gérées ; la structure de blocs reste en place.
- Dimensions admises : de 8×8 à 64×64, avec une surface maximale de 4 096 blocs. Un joueur peut posséder au plus 2 enclos (hors administrateur), dans la limite globale non contournable de 32. `mineplugin.eleveur.admin` autorise la maintenance des enclos d'autres joueurs.

### /village (permission `mineplugin.village.use`, opérateur par défaut)
- `/village` : génère un village semi-organique autour du joueur avec routes, place centrale, maisons, fermes, muraille, arbres, mares, jardins et mobilier. Les lanternes visibles sont complétées par un audit de lumière de bloc qui sécurise aussi les intérieurs, toitures, chemins de ronde et abords immédiats avec des blocs `LIGHT` invisibles.
- Le donjon contient désormais un escalier vers une grande salle des coffres : double coque d'obsidienne, parement de quartz/diorite façon marbre, or, tapis, éclairage, registre central, sas à doubles portes et 60 coffres individuels. Une barre `Salle des coffres` apparaît seulement dans la chambre principale.
- Une collecte fiscale globale choisit automatiquement une ressource aléatoire entre les intervalles `village.treasury.tax.min-interval-seconds` et `max-interval-seconds` (1 à 60 secondes par défaut). Tous les matériaux utilisables comme objets sont éligibles, notamment potions, œufs, blocs techniques et spawners. Les échéances hors ligne deviennent des collectes en attente sans forcer le chargement des chunks.
- Le créateur de `/village` est le roi de cette ville : lui seul ouvre les portes et coffres par défaut. `mineplugin.village.treasury.admin` autorise la maintenance administrative. Hoppers, pistons, explosions, fluides et altérations naturelles ne peuvent pas contourner la protection.
- Quatre sentinelles fixes de la garnison tiennent les postes de la chambre forte. Elles ne patrouillent pas, mais combattent les monstres à portée et ripostent ensemble lorsqu'un joueur frappe l'une d'elles.
- `/village undo` : annule la dernière génération effectuée par son propriétaire. Le rollback retire aussi l'état fiscal et les quatre sentinelles avant de restaurer chaque bloc. `mineplugin.village.admin` autorise l'annulation par un administrateur.
- Une construction interrompue par l'arrêt du plugin, ou une création incomplète de la banque/garnison, est automatiquement annulée afin de ne jamais laisser de village partiel.

### /armure (permission `mineplugin.armure.use`, opérateur par défaut)
- Donne l’Armure du roi GIDON (netherite enchantée). Le plugin identifie désormais ses pièces par une donnée interne et non par leur simple nom.
- En portant l’ensemble : effets rafraîchis à courte durée (vision nocturne, résistance au feu, respiration, santé/force), et jusqu'à 4 loups gardes temporaires apparaissent quand le joueur est touché.

### /marchand (permission `mineplugin.marchand.spawn`)
- Invoque un PNJ marchand invulnérable à l’emplacement du joueur. Les offres proviennent de `plugins/MinePlugin/marchand.yaml`; clique le PNJ pour ouvrir l’interface d’échanges.
- `/marchand reload` exige `mineplugin.marchand.admin`. Les plafonds d'utilisation sont conservés dans `merchant-caps.yml` après un rechargement ou un redémarrage.
- Une offre ne peut pas produire plus de 4 096 objets ni plus de 64 piles par échange ; les entrées invalides sont ignorées avec un message explicite dans les logs.

### /niveau — progression globale
- `/niveau` ou `/niveau info` : affiche le niveau global, l'XP du palier et les quatre bonus du joueur. Les alias `/level` et `/progression` sont disponibles.
- Le niveau commence à 1 et se termine à 100. Le niveau 1 est la référence sans bonus ; chaque level-up ajoute par défaut **+1 % de points de vie maximum, +1 % de durée d'apnée, +1 % de dégâts causés et +1 % de vitesse de minage**. Le niveau 100 correspond donc à +99 %.
- L'XP se gagne par le minage et le bûcheronnage configurés, les cultures mûres, les éliminations, la pêche, la reproduction, l'apprivoisement, la tonte, l'enchantement, la récupération d'une cuisson, le commerce et les progrès Minecraft.
- À chaque gain, une sidebar compacte apparaît en haut à droite avec le niveau, la barre et l'XP du palier, puis disparaît après quatre secondes par défaut. Le scoreboard précédent est restauré ; une actionbar sert de repli si la sidebar n'est pas disponible.
- Anti-abus : aucun gain en Créatif/Spectateur, blocs reposés, formés ou générés par `/village` marqués dans le chunk, suivi des pistons et explosions, multiplicateurs selon l'origine des mobs, plafond par gain et par minute, et mondes désactivables.
- Administration (`mineplugin.progression.admin`, opérateur par défaut) : `/niveau info <joueur>`, `/niveau addxp <joueur> <quantité>`, `/niveau setlevel <joueur> <1-100>` et `/niveau reset <joueur>`.

### /job
- `/job mineur` : choisit le métier de mineur.
- `/job` ou `/job info` : affiche métier, niveau, XP et nombre de mines débloquées. L’XP se gagne en minant à la pioche; +1 slot de mine tous les 10 niveaux (jusqu’à 10).

### /minegus
- `/minegus` : affiche une aide rapide avec toutes les commandes principales du plugin, y compris les commandes restreintes marquées `[permission]` ou `[admin]`.
- `/minegus fix forestier|golems` (permission `mineplugin.admin`) : commande de maintenance qui supprime les doublons de forestiers de /foret ou de golems gardes taggés dans tous les mondes.

## Persistance des données
Les fonctionnalités persistantes sauvegardent leurs informations (YAML) dans `plugins/MinePlugin/` (`sessions.yml`, `farms.yml`, etc.). Le tutoriel conserve uniquement une destination de secours dans `tutorial-returns.yml`, supprimée après le retour réussi ; ses mondes ne sont jamais sauvegardés. Les autres modules restaurent leurs PNJ et structures au redémarrage. La progression globale est stockée séparément dans `progression.yml` (UUID, dernier nom connu, XP totale et état transitoire de santé nécessaire à une restauration propre) et les métiers restent dans `jobs.yml`. Son écriture est atomique et différée ; un YAML invalide est mis en quarantaine avec un suffixe `.corrupt-<horodatage>.bak` au lieu d’être écrasé. Les deux gardes de `/garde` ne sont jamais sauvegardés et tout garde résiduel est retiré au chargement d’un chunk après un redémarrage.

## Structure du projet
- `src/main/java/` : code source du plugin.
- `src/main/resources/` : `plugin.yml`, `config.yml` par défaut.
- `docs/IDEES.md` : idées et notes.
- `target/` : artefacts de build (ne pas modifier/committer).

## Architecture du projet (qui fait quoi)
- `org.example.MinePlugin` : point d’entrée. Enregistre `/army` et `/ping`, instancie les modules (`Mineur`, `Agriculture`, `Foret`, `Village`, `Eleveur`, `Armure`) ainsi que les gestionnaires de métier et de progression globale, puis initialise `TutorialManager` et ferme proprement l’ensemble dans `onDisable`.
- `org.example.tutorial` : monde privé généré, scènes par lots, DonjBot animé, scénario, protections, rappel à 15 blocs, retour transactionnel et suppression sécurisée des mondes.
- `org.example.progression` : courbe 1–100, collecte multi-actions, bonus attributaires/dégâts, HUD sidebar temporaire, anti-farming des blocs et persistance atomique dans `progression.yml`.
- `org.example.Mineur` : gère `/mineur`. Bâton de sélection (2 blocs même Y), cadre + coffres + PNJ mineur + golems, minage vertical, salle d’arrivée et infrastructure du tunnel, persistance `sessions.yml`, arrêt si tous les coffres cassés.
- `org.example.Agriculture` : gère `/champ`. Crée terres labourées + irrigation, PNJ fermier + golems, récolte/stockage auto, persistance `farms.yml`.
- `org.example.Foret` : gère `/foret`. Grille de pousses, récolte et replantation auto, coffres d’angle, persistance `forests.yml`.
- `org.example.Eleveur` : gère `/eleveur`. Génère un ranch (clôtures, coffres, spawners), limite d’animaux par espèce (depuis `config.yml`), ramassage et stockage, scoreboard, persistance `ranches.yml`.
- `org.example.Village` : gère `/village` et `undo`. L'orchestration par file de `Runnable` couvre routes, place, lots, bâtiments, spawners, muraille, décoration et audit lumineux, avec suivi de chaque bloc pour le rollback.
- `org.example.Batiments` : helpers statiques de bâtiments (maisons pivotées, toitures, détails). Écrit via `Village#setBlockTracked` pour permettre l’undo.
- `org.example.Golem` : sentinelle golem avec rayon de patrouille, retour au point d’ancrage (pathfinder/teleport), attributs ajustés.
- `org.example.RoyalGuardManager` : gère `/garde`, les deux Husks personnels, leur suivi, leur combat, leur rappel et leur respawn différé.
- `org.example.Armure` : gère `/armure`. Donne l’équipement “roi GIDON”, applique/retire les buffs en boucle, invoque des loups gardes à la prise de dégâts et programme leur disparition.
- `org.example.TeleportUtils` : `safeTeleport` (utilise `teleportAsync` si dispo, sinon `teleport`).
- `org.example.village.Disposition` : répartition des lots et planification des tâches (routes, maisons, fermes et lampadaires alternés).
- `org.example.village.VillageDecorationBuilder` : réserve les interstices puis ajoute arbres, vergers éclairés, massifs, mares, jardins d'herbes et mobilier sans collision.
- `org.example.village.VillageLightingBuilder` : pose les lumières décoratives finales et audite les surfaces sombres par petites tuiles afin de bloquer le spawn hostile naturel.
- `org.example.village.HouseBuilder` : génère maisons/fermes/lampadaires; renvoie des actions différées.
- `org.example.village.TerrainManager` : aplanit/remblaie le terrain; utilitaire `SetBlock` safe.
- `org.example.village.WallBuilder` : construit la muraille (corps, crénelages, portes, torches) en tâches différées.
- `org.example.village.VillageEntityManager` : spawn/cleanup des PNJ et golems, taggage par village, limitation de population périodique.

## Développement
- Vérifier et construire : `mvn -q verify` (tests JUnit 5 sous `src/test/java/`, puis JAR dans `target/`).
- Lancer localement : copier le JAR dans `plugins/` d’un serveur Paper 1.21.4 puis démarrer le serveur.
- Style : Java 21, 4 espaces, accolades sur la même ligne, classes `PascalCase`, méthodes/champs `camelCase`, constantes `UPPER_SNAKE_CASE`.
- Commits : français, présent, concis (ex. « Ajoute collecte du champ »). Ne modifiez jamais `target/*.jar`.
- Voir `AGENTS.md` pour des consignes plus détaillées.

## Guide développeur (amélioré)

### Développement rapide
- Cloner, utiliser Java 21, puis `mvn -q verify` → JAR vérifié dans `target/`.
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
- Tutoriel : lancer `/tuto`, vérifier chaque décor et le déplacement de DonjBot ; s'éloigner de plus de 15 blocs ; tenter une autre commande et une interaction ; quitter avec `/tuto` à plusieurs étapes ; tester une déconnexion puis un redémarrage pendant la visite et confirmer le retour exact ainsi que la suppression du dossier `minegus_tuto_*`.
- Sanity de base : `/ping`, `/army` (spawn + disparition programmée).
- Gardes royaux : exécuter `/garde`, vérifier les deux positions latérales arrière et tourner rapidement pour confirmer que les gardes dégagent la caméra ; frapper successivement plusieurs mobs puis vérifier qu’ils sont traités dans l’ordre jusqu’à leur mort ; laisser un zombie ou un raider prendre le joueur pour cible sans recevoir de coup et confirmer l’interception proactive ; tester une flèche tirée par le propriétaire, un animal apprivoisé, une attaque PvP, une cible qui sort de la laisse de combat, le rappel hors combat au-delà de 20 blocs et le changement de monde ; approcher ensuite un golem de fer naturel de village, confirmer la neutralité puis provoquer une tentative de frappe ; enfin bloquer un chemin, tuer un garde, vérifier son retour après 20 secondes, renvoyer le duo et confirmer l’annulation des respawns.
- Mineur : `/mineur`, sélectionner deux blocs au même Y, vérifier cadre + coffres + PNJ, changer `/mineur vitesse rapide`, puis contrôler la salle d’arrivée, les cadres, les lumières, le drainage et le verre aux contacts fluides ou ouvertures de grotte lors du passage au tunnel.
- Progression globale : casser un minerai naturel puis un minerai posé, tuer un mob naturel puis issu d'un spawner, récolter une culture mûre et pêcher ; vérifier `/niveau`, le HUD temporaire, la restauration de l'ancienne sidebar, la persistance après redémarrage et les quatre bonus aux niveaux 2 puis 100.
- Champ : `/champ`, récolte/stockage, suppression des coffres → arrêt automatique.
- Forêt : `/foret`, coupe/replantation automatique, persistance après redémarrage.
- Éleveur : `/eleveur`, `/eleveur list`, `/eleveur delete <id>`; vérifier limites d’animaux configurées.
- Village : exécuter `/village`, descendre l’escalier du donjon, vérifier les 60 coffres, le registre, la barre de zone et les quatre sentinelles fixes ; attendre plusieurs collectes, redémarrer le serveur puis contrôler la persistance et le rattrapage borné ; tester l’accès du roi, le refus d’un autre joueur, les hoppers/pistons/explosions/fluides, l’attaque d’une sentinelle et enfin `/village undo` pour le rollback complet.
- Marchand : spawn avec `/marchand`, ouverture via `/marchand open <joueur>`, tester une offre avec cap quotidien et vérifier le message de blocage après dépassement.

## Migration depuis une version antérieure au correctif de sécurité
- Les anciennes pièces de l'Armure GIDON ne possèdent pas l'identifiant interne de sécurité : exécutez de nouveau `/armure` pour recevoir le nouvel ensemble.
- Les anciens champs et enclos sans propriétaire dans `fields.yml` ou `ranches.yml` restent chargés, mais leur maintenance est réservée aux administrateurs. Recréez-les, ou ajoutez une clé `owner` contenant l'UUID du propriétaire après sauvegarde du fichier.
- Les écritures sensibles créent désormais une sauvegarde `*.bak` dans le même dossier. Conservez-la lors d'une migration.
- Le fichier `merchant-caps.yml` est créé automatiquement pour conserver les quotas du marchand.

## Dépannage rapide
- Le plugin ne se charge pas : vérifier la version Paper (1.21.4), `java -version` (21) et les logs `logs/latest.log`.
- Commande inconnue : confirmer la permission (`op` par défaut sur la plupart des commandes) et que `plugin.yml` est bien dans le JAR généré.
- Menus vides du marchand : valider la syntaxe de `plugins/MinePlugin/marchand.yaml` (indentation, clés `categories:` et `offers:`).
- Caps du marchand qui ne se reset pas : vérifier `merchant.reset_time_utc` (UTC) et attendre la minute suivante après l’heure cible.
- Golems/PNJ en double : utiliser `/minegus fix forestier` ou `/minegus fix golems`.
- Performances : limiter la taille des zones (`/mineur`, `/foret`, `/champ`) et éviter le force‑load de chunks.

## Intégration CI/CD
Le workflow `.github/workflows/maven.yml` exécute :
- `mvn -B --no-transfer-progress verify` avec Temurin 21 ;
- les tests unitaires du lanceur Python avec Python 3.12.

Publiez uniquement `target/MineGus-<version>.jar` produit par ce workflow. Les JAR précompilés présents à la racine du dépôt ne doivent pas être considérés comme une source fiable et doivent être remplacés par un artefact reconstruit.

## Licence
MIT — voir `LICENSE`.

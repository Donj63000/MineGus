# MinePlugin

Ce dépôt contient un plugin Paper pour Minecraft 1.21.x permettant d'automatiser diverses tâches (minage, agriculture, forêt, élevage, etc.).
Ce mod est actuellement en bêta et continuera d'évoluer. Il est développé par un seul développeur amateur qui prend son temps. Pour l'instant, il est surtout destiné aux petits serveurs entre amis.

## Prérequis

- **Java 17**
- **Maven 3.x**
- Un serveur **Paper 1.21.x**

## Compilation du plugin

1. Clonez ce dépôt puis placez-vous à la racine du projet.
2. Exécutez `mvn clean package`.
   - Le fichier `target/MinePlugin.jar` est alors généré.
   - Vous pouvez aussi lancer `mvn clean package -P export-to-server` pour copier automatiquement l'artefact dans le dossier `plugins` défini dans `pom.xml`.

## Installation du plugin (.jar)

1. Récupérez le fichier `MinePlugin.jar` (généré à l'étape précédente ou fourni dans les releases).
2. Copiez ce fichier dans le dossier `plugins/` de votre serveur Paper.
   - Exemple : `/home/user/paper/plugins/MinePlugin.jar`.
3. Lancez ou redémarrez le serveur.
4. Vérifiez la présence du plugin à l'aide de la commande `/plugins`.
5. Lors du premier démarrage, un dossier `plugins/MinePlugin/` est créé pour stocker les données et configurations.

## Mode en jeu

Une fois le plugin installé et le serveur relancé :
1. Connectez-vous avec les droits d’opérateur.
2. Tapez `/ping` pour confirmer que MinePlugin est actif.
3. Utilisez ensuite les commandes listées plus bas pour créer vos premières zones automatisées.
4. Pour arrêter une zone (mine, champ, forêt ou ranch), retirez tous les coffres associés.
5. Les données sont sauvegardées dans `plugins/MinePlugin/` et restaurées au redémarrage.

## Commandes disponibles

| Commande | Description rapide |
|----------|-------------------|
| `/ping` | Vérifie que le plugin répond (renvoie « Pong ! »). |
| `/army` | Invoque temporairement 5 loups et 2 golems pour vous protéger. |
| `/mineur` | Lance un mineur automatique sur une zone définie. |
| `/champ` | Crée un champ agricole automatisé. |
| `/foret` | Met en place une petite forêt avec récolte automatique. |
| `/village` | Génère un village complet près du joueur. |
| `/eleveur` | Construit un enclos d’élevage automatisé. |
| `/armure` | Donne l’Armure légendaire du roi GIDON. |

## Détails des commandes

### `/ping`

Simple commande de test : entrez `/ping` dans le chat pour vérifier que le plugin est bien chargé. Le message « Pong ! » doit apparaître.

### `/army`

Fait apparaître autour de vous cinq loups apprivoisés ainsi que deux golems. Ils protègent le joueur pendant cinq minutes puis disparaissent automatiquement.
Utilisez cette commande lorsque vous explorez des zones dangereuses.

### `/mineur`

1. Tapez `/mineur` pour recevoir un bâton nommé « Sélecteur de mine ».
2. Sélectionnez deux blocs au même niveau (clic gauche puis clic droit) : cela définit la largeur et la longueur de la zone.
3. Un PNJ mineur et deux golems apparaissent, entourés de coffres placés aux coins.
4. Le mineur creuse verticalement jusqu’à la couche -58 et dépose tous les minerais dans les coffres.
5. Tant qu’au moins un coffre est présent, la session continue après un redémarrage du serveur.

### `/champ`

1. Exécutez `/champ` pour obtenir le bâton « Sélecteur de champ ».
2. Cliquez deux blocs (même Y) pour délimiter l’emplacement de votre champ.
3. Le plugin transforme la zone en terres labourées, ajoute un système d’irrigation, place des coffres et fait apparaître un fermier avec deux golems.
4. Le fermier replante et récolte automatiquement les cultures qui sont stockées dans les coffres.
5. Si tous les coffres sont détruits, le champ disparaît et la session est stoppée.

### `/foret`

1. Faites `/foret` pour recevoir un bâton « Sélecteur de forêt ».
2. Sélectionnez deux coins au même niveau afin de délimiter la future forêt.
3. Un forestier et deux golems sont générés. Les arbres poussent naturellement et sont récoltés dès qu’ils sont matures.
4. Chaque sapling est replanté automatiquement. Les bûches et pousses récoltées sont stockées dans les coffres aux coins.
5. Si les coffres sont retirés, la forêt n’est plus entretenue et les PNJ disparaissent.

### `/village`

Exécutez simplement `/village` à l’emplacement souhaité : plusieurs maisons, chemins et PNJ sont créés autour de vous afin de former un petit village vivant.

### `/eleveur`

1. Lancez `/eleveur` pour obtenir le bâton « Sélecteur d’élevage ».
2. Sélectionnez deux blocs au même niveau pour définir la zone du ranch.
3. Le plugin construit un enclos complet (clôtures, coffres, PNJ éleveur, spawners et golems de garde).
4. Les animaux qui dépassent la limite configurée sont automatiquement abattus, et la viande est ramassée par le PNJ puis stockée dans les coffres.
5. Tout comme les autres fonctionnalités, l’état du ranch est sauvegardé dans `ranches.yml`.

### `/armure`

La commande `/armure` vous donne un ensemble complet d’équipement. Tant que vous portez les quatre pièces (casque, plastron, pantalon et bottes du roi GIDON) vous bénéficiez de plusieurs effets permanents : vision nocturne, résistance au feu, respiration aquatique, augmentation de la vie maximale et force. Les effets disparaissent dès qu’une pièce est retirée.

## Persistance des données

Les différentes fonctionnalités sauvegardent leurs informations dans le dossier `plugins/MinePlugin/` (fichiers YAML tels que `sessions.yml`, `farms.yml`, etc.). Ceci permet de restaurer PNJ et structures lors d'un redémarrage du serveur.

## Structure du projet

- Les sources Java se trouvent dans `src/main/java`.
- Le fichier `plugin.yml` décrivant les commandes est situé dans `src/main/resources`.
- Des notes et idées supplémentaires sont regroupées dans `docs/IDEES.md`.

## Licence

Ce projet est distribué sous la licence MIT. Consultez le fichier `LICENSE` pour plus de détails.

Bon jeu !

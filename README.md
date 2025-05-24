# MinePlugin

Ce dépôt contient un plugin Paper pour Minecraft 1.21.x permettant d'automatiser diverses tâches (minage, agriculture, forêt, élevage, etc.).

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

## Utilisation du plugin

Une fois le plugin chargé, vous pouvez vérifier son fonctionnement :
1. Connectez-vous à votre serveur Minecraft.
2. Exécutez la commande `/ping` ; le plugin devrait répondre « Pong ! ».
3. Les autres commandes ci-dessous permettent d'automatiser différentes tâches.

## Commandes disponibles

- `/army` : fait apparaître 5 loups et 2 golems protecteurs pendant quelques minutes.
- `/ping` : simple commande de test renvoyant « Pong ! ».
- `/mineur [Largeur]x[Hauteur]` : génère une zone de minage automatisé.
- `/champ [Largeur]x[Longueur]` : crée un champ cultivé et automatisé.
- `/foret [Largeur]x[Longueur]` : plante et récolte automatiquement des arbres.
- `/village` : fait apparaître un petit village de PNJ.
- `/eleveur` : installe un enclos d'élevage automatisé.
- `/armure` : donne l'Armure du roi GIDON au joueur.

## Persistance des données

Les différentes fonctionnalités sauvegardent leurs informations dans le dossier `plugins/MinePlugin/` (fichiers YAML tels que `sessions.yml`, `farms.yml`, etc.). Ceci permet de restaurer PNJ et structures lors d'un redémarrage du serveur.

## Structure du projet

- Les sources Java se trouvent dans `src/main/java`.
- Le fichier `plugin.yml` décrivant les commandes est situé dans `src/main/resources`.

Bon jeu !

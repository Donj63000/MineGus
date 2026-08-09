# AUGUSTIN — Paper Launcher

Lanceur local Windows pour Paper 1.21.4 et Minecraft Java 1.21.4. Le lanceur démarre `paper.jar`, affiche la console en direct, envoie les commandes Paper, arrête le serveur proprement et ouvre le Launcher Minecraft officiel après vérification du profil `MineGus`.

## Ce qui a été corrigé

L'ancienne interface exécutait plusieurs opérations bloquantes directement dans le thread Tkinter : test réseau du port toutes les 250 ms, exécution de `java -version`, création du processus Java et lectures de `latest.log`. Selon Windows, l'antivirus, le disque ou la JVM installée, un clic sur **Démarrer** pouvait donc figer toute la fenêtre pendant plusieurs secondes.

La version actuelle sépare strictement l'interface et les opérations système :

- la détection de Java, le test du port, le démarrage de Paper, l'arrêt et la lecture des journaux passent par un pool de threads ;
- Tkinter ne fait plus que lire des instantanés non bloquants et mettre à jour les widgets ;
- une barre de progression et des états détaillés rendent chaque phase visible ;
- les logs sont transférés par une file bornée et affichés par lots, afin qu'un gros démarrage Paper ne sature ni la mémoire ni l'interface ;
- la console limite son historique à 8 000 lignes et préserve le défilement manuel ;
- le suivi de `latest.log` gère les rotations, troncatures et lignes écrites partiellement ;
- les lignes déjà reçues par `stdout` ne sont plus relues en double après l'arrêt ;
- l'état **En ligne** est détecté par le port ou par le message Paper `Done (...)` ;
- un arrêt inattendu remonte le code de sortie et un diagnostic clair ;
- la fermeture pendant un démarrage attend la création du processus, puis l'arrête proprement ;
- l'ancienne version de l'exécutable est sauvegardée automatiquement pendant un déploiement.

## Interface

L'interface comporte désormais :

- un état global visible avec couleur et détail de la phase en cours ;
- les indicateurs du port, de Java, du PID et du temps d'activité ;
- des actions clairement séparées : démarrer, arrêter, redémarrer, forcer l'arrêt et ouvrir Minecraft ;
- une console sombre avec couleurs par niveau, barres de défilement, menu contextuel et option d'auto-défilement ;
- un historique des commandes avec les touches `↑` et `↓` ;
- une mise à l'échelle DPI et une barre de titre sombre sous Windows quand le système l'autorise.

## Fichiers à remplacer

Depuis la racine du projet, les principaux fichiers sont :

```text
tools/augustin_launcher/augustin_launcher/app.py
tools/augustin_launcher/augustin_launcher/controller.py
tools/augustin_launcher/augustin_launcher/core.py
tools/augustin_launcher/build.ps1
tools/augustin_launcher/tests/test_controller.py
tools/augustin_launcher/tests/test_core.py
```

Aucune bibliothèque graphique externe n'est requise : l'application utilise uniquement la bibliothèque standard Python. PyInstaller est nécessaire uniquement pour fabriquer le `.exe`.

## Tester le code source

Depuis `tools\augustin_launcher` :

```powershell
python -m unittest discover -s tests -t . -v
```

Tous les tests doivent terminer avec `OK`.

## Compiler l'exécutable

Fermer d'abord l'ancien launcher, puis lancer depuis la racine du dépôt :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\augustin_launcher\build.ps1
```

L'exécutable est généré ici :

```text
C:\tmp\augustin_launcher_build\dist\AugLauncher.exe
```

Si PyInstaller n'est pas installé :

```powershell
python -m pip install --upgrade pyinstaller
```

## Compiler et déployer automatiquement

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\augustin_launcher\build.ps1 -Deploy
```

Le script :

1. détecte Python 3 ;
2. exécute les tests ;
3. vérifie PyInstaller ;
4. compile le lanceur ;
5. sauvegarde l'ancien exécutable avec un horodatage ;
6. migre automatiquement l'ancien nom `AUGUSTIN_Launcher.exe` vers une sauvegarde ;
7. déploie `AugLauncher.exe` dans le dossier PaperServer.

Pour indiquer explicitement le dossier serveur :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\augustin_launcher\build.ps1 `
  -Deploy `
  -ServerDirectory "D:\Minecraft\PaperServer"
```

## Configuration facultative

Le lanceur reconnaît les variables d'environnement suivantes :

```text
AUGUSTIN_SERVER_DIR   chemin du dossier contenant paper.jar
AUGUSTIN_JAVA         chemin complet vers java.exe
JAVA_HOME             dossier d'une JDK Java 21 ou supérieure
```

Exemple temporaire dans PowerShell :

```powershell
$env:AUGUSTIN_SERVER_DIR = "D:\Minecraft\PaperServer"
$env:AUGUSTIN_JAVA = "C:\Program Files\Eclipse Adoptium\jdk-21\bin\java.exe"
.\AugLauncher.exe
```

## Comportement de sécurité

- Le launcher ne contrôle que le processus Paper qu'il a lui-même créé.
- Si le port `25565` est déjà utilisé, l'état devient **Serveur externe détecté** et les commandes sont désactivées.
- **Arrêter** envoie toujours la commande Paper `stop` avant d'attendre la fermeture.
- **Forcer l'arrêt** n'est proposé qu'après expiration du délai d'arrêt propre et demande une confirmation explicite.
- Le launcher ne modifie pas les mondes, les plugins, `server.properties`, RCON ni `paper.jar`.

## Utilisation

1. Ouvrir `AugLauncher.exe` depuis le dossier contenant `paper.jar`.
2. Cliquer sur **Démarrer**. La fenêtre reste fluide pendant la recherche de Java et le chargement de Paper.
3. Attendre l'état **En ligne**.
4. Saisir les commandes Paper sans `/` dans le champ inférieur.
5. Utiliser **Arrêter** ou **Redémarrer** pour préserver les mondes.
6. Utiliser **Lancer Minecraft** pour vérifier le profil `MineGus 1.21.4` et ouvrir le Launcher officiel.

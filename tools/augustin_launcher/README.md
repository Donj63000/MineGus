# AUGUSTIN Launcher

Ce dossier contient le code source du lanceur local Paper/Minecraft.

## Fonctions

- Démarre Paper 1.21.4 avec Java 21 et `-Xms2G -Xmx2G`.
- Affiche les logs en temps réel et accepte les commandes Paper lorsque le launcher a démarré le serveur.
- Arrête et redémarre Paper proprement avec la commande `stop`.
- Détecte un serveur déjà lancé sur le port `25565` et ne le contrôle pas.
- Vérifie le profil Minecraft Java `MineGus` en `1.21.4`, puis ouvre le Launcher officiel.

Le launcher ne modifie ni les mondes, ni les plugins, ni `server.properties`, ni RCON. Il ne remplace jamais `SERVEUR.exe`.

## Construire et déployer

Depuis la racine du dépôt :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\augustin_launcher\build.ps1 -Deploy
```

Le script lance les tests, génère `AUGUSTIN_Launcher.exe` avec PyInstaller, puis le dépose dans :

`C:\Users\nodig\Desktop\SERVEUR MINECRAFT AUGUSTIN\PaperServer`

Sans `-Deploy`, l'exécutable reste dans `C:\tmp\augustin_launcher_build\dist`.

## Utilisation

1. Ouvrir `AUGUSTIN_Launcher.exe` depuis le dossier `PaperServer`.
2. Cliquer sur **Démarrer Serveur** si le statut est **Arrêté**.
3. Envoyer les commandes Paper dans le champ inférieur lorsque le launcher contrôle le serveur.
4. Utiliser **Arrêter** ou **Redémarrer** pour sauvegarder proprement les mondes.
5. Cliquer sur **Lancer Minecraft**, puis vérifier le profil **MineGus (1.21.4)** dans le Launcher officiel avant de cliquer sur Jouer.

Si Paper a été lancé ailleurs, le statut devient **Serveur externe détecté** : la console suit les logs, mais aucune commande ni arrêt n'est disponible par sécurité.

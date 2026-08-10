# Environnement local du projet

## Serveur Paper de développement

- Le dossier du serveur Minecraft AUGUSTIN est fourni par la variable d'environnement `AUGUSTIN_SERVER_DIR`; aucun chemin propre à une machine ne doit être versionné.
- Le JAR installé est `${AUGUSTIN_SERVER_DIR}\plugins\MineGus-1.1-SNAPSHOT.jar`.
- Lorsqu'une mise à jour du plugin du serveur est demandée, je construis et teste d'abord `target\MineGus-1.1-SNAPSHOT.jar`, puis je déploie cet artefact dans le dossier `plugins` du serveur. Les JAR générés ne sont pas une source et ne doivent pas être commités.
- Je vérifie les empreintes SHA-256 après la copie. Je ne démarre ou ne redémarre pas le serveur sans demande explicite.

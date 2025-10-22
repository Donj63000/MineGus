# Repository Guidelines

## Contexte actuel
- Plugin Paper : `MinePlugin`
- Version Minecraft ciblée : 1.21.4 (Java 17)
- Objectif : automatiser minage, agriculture, forêt, élevage, villages PNJ et armure spéciale.
- Build & tests : Maven 3.9+, JUnit 5, MockBukkit pour les tests unitaires.

## Priorités agent
- 1️⃣ Requête directe de l’utilisateur.
- 2️⃣ `instructions.txt` (prompt local) — à relire avant chaque modification majeure.
- 3️⃣ Ce document (`AGENTS.md`).
- Toujours confirmer les hypothèses ambiguës avant d’avancer.

## Checklist rapide
- Lire/relire `instructions.txt` au démarrage et après chaque nouvelle consigne.
- Limiter la portée des changements aux demandes explicites.
- Après modifications Java/ressources : exécuter `mvn -q package` (signaler toute erreur ou indisponibilité).
- Documenter les tests manuels pertinents (`/ping`, `/army`, `/mineur`, `/champ`, `/foret`, `/village`, `/eleveur`).
- Ne jamais modifier `target/` ni les artefacts générés.

## Structure du projet
- Code Java : `src/main/java/`
- Ressources Paper : `src/main/resources/` (`plugin.yml`, `config.yml` par défaut)
- Tests automatisés : `src/test/java/`
- Notes manuelles : `docs/IDEES.md`
- Artefacts de build : `target/` (lecture seule)

## Flux de développement
- Compiler : `mvn -q package` → `target/MineGus-<version>.jar`
- Tests unitaires : `mvn -q test`
- Nettoyage en cas d’incohérences : `mvn -q clean package`
- Déploiement local : copier le JAR dans `plugins/` d’un serveur Paper 1.21.4 puis redémarrer.

## Style & nommage
- Java 17, indentation 4 espaces, accolades sur la même ligne.
- Classes en `PascalCase`, méthodes/champs en `camelCase`, constantes en `UPPER_SNAKE_CASE`.
- Pas d’identifiants à une lettre ; suivre les patterns locaux.
- Appliquer mot pour mot le code fourni par les mainteneurs si présent.

## Tests recommandés
- Unitaires : ajouter des tests JUnit 5 ciblés dans `src/test/java/` (ex. `ArmyCommandTest`).
- Manuels : `/ping`, `/army`, `/mineur`, `/champ`, `/foret`, `/village`, `/eleveur`.
- Vérifier que la suppression de tous les coffres désactive bien chaque zone.
- Toujours redémarrer le serveur Paper après déploiement pour valider la persistance.

## Modules clés
- `MinePlugin` : point d’entrée, enregistre les commandes et orchestre les managers.
- `Mineur`, `Agriculture`, `Foret`, `Eleveur` : gestion des zones automatisées + persistance YAML.
- `Village` (+ sous-packages) : génération asynchrone du village, muraille, PNJ.
- `Armure` : armure légendaire et gardiens.
- `TeleportUtils`, `Batiments`, utilitaires divers.

## Notes Paper/Bukkit
- Interagir avec l’API Bukkit uniquement sur le thread principal.
- Déléguer les tâches lourdes ou bloquantes via le scheduler (async).
- Opérer uniquement dans des chunks déjà chargés ; pas de force-load gratuit.
- Persister juste ce qui est nécessaire sous `plugins/MinePlugin/`.
- Avant toute action destructive, décrire le plan et demander confirmation si doute.

## Ressources à consulter
- `README.md` : documentation détaillée du plugin et commandes.
- `docs/IDEES.md` : cahier des charges gameplay (maisons, muraille, PNJ, etc.).
- `target/surefire-reports/` : diagnostics en cas d’échec Maven.

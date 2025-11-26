# Repository Guidelines

## TL;DR opérationnel
- Lire la requête utilisateur puis `instructions.txt`; caler la portée minimale avant de toucher aux fichiers.
- Vérifier l'état local via `git status --short`; ne pas toucher à `target/` ni aux artefacts générés.
- Préparer les classes/ressources à modifier, annoncer un plan dès que plusieurs étapes ou zones sensibles sont impliquées.
- Code Java 17, indentation 4 espaces, pas de refactor massif; utiliser `rg`/`apply_patch` pour des changements ciblés.
- Après toute modif Java/ressource : `mvn -q package` (puis `mvn -q test` si pertinent) et rapporter les résultats.
- Restituer en listant les changements et les tests manuels (`/ping`, `/army`, etc.) effectués ou non.

## Vue d’ensemble
- Plugin Paper : `MinePlugin`
- Cible : Minecraft 1.21.4 sur Java 17
- Objectif : automatiser minage, agriculture, forêt, élevage, villages PNJ et armure spéciale
- Stack outillage : Maven 3.9+, JUnit 5, MockBukkit, Paper API 1.21.4
- Docs à relire en amont : `README.md`, `docs/IDEES.md`

## Priorités & communication
- 1️⃣ Requête directe de l’utilisateur
- 2️⃣ `instructions.txt` (à relire au démarrage et après toute nouvelle consigne)
- 3️⃣ Ce guide (`AGENTS.md`)
- Confirmer les hypothèses ambiguës, annoncer les actions destructrices et partager un plan avant de modifier massivement une zone sensible.
- Restituer de façon concise : fichiers touchés + comportement attendu + résultats des tests.

### Déclencheurs de confirmation
- Effacement ou réécriture d’un fichier conséquent
- Modifications sur la persistance (`plugins/MinePlugin/*.yml`)
- Changements impactant plusieurs systèmes (ex. commandes + persistance + scheduler)

## Checklist rapide
- Vérifier `git status` et prendre connaissance des changements locaux
- Relire `instructions.txt` + requête utilisateur et limiter la portée aux demandes explicites
- Préparer les fichiers à toucher (classes Java, YAML, docs) et noter les impacts attendus
- Utiliser `rg` pour chercher et `apply_patch` pour des edits ciblés; ne jamais modifier `target/` ni les artefacts générés
- Après toute modification Java/ressource : `mvn -q package` (remonter les erreurs)
- Chaque modification doit etre accompagnee de tests unitaires pertinents
- Documenter les tests unitaires/manuels exécutés (`/ping`, `/army`, `/mineur`, `/champ`, `/foret`, `/village`, `/eleveur`)

## Flux de travail conseillé
1. Découvrir : lire `README.md`, `docs/IDEES.md` et inspecter les classes concernées
2. Planifier : préciser les points à traiter, annoncer les hypothèses et préparer la stratégie (commande, listener, data, tests)
3. Implémenter : coder avec le style local, éviter toute refactorisation non demandée et rester thread-safe
4. Valider : `mvn -q package` (+ `mvn -q test` si pertinent) et, côté manuel, relancer les commandes liées à la feature
5. Restituer : décrire les modifications, les tests réalisés et les prochaines étapes éventuelles

## Structure du projet
- Code Java : `src/main/java/`
- Ressources Paper : `src/main/resources/` (`plugin.yml`, `config.yml` par défaut)
- Tests automatisés : `src/test/java/`
- Notes gameplay : `docs/IDEES.md`
- Artefacts build : `target/` (lecture seule)

## Standards code & nommage
- Java 17, indentation 4 espaces, accolades ouvertes sur la même ligne
- Classes en `PascalCase`, méthodes/champs en `camelCase`, constantes en `UPPER_SNAKE_CASE`
- Pas d’identifiants à une lettre ; préférer des noms descriptifs
- Respecter les patterns existants (gestionnaires, commandes, tasks) et appliquer mot pour mot tout code fourni par les mainteneurs
- Éviter le blocage du thread principal : scheduler async pour IO lourdes, synchronisation réduite au strict nécessaire

## Tests recommandés
- Unitaires : privilégier JUnit 5 + MockBukkit (`src/test/java/`). Exemple : `ArmyCommandTest`, managers isolés, validation YAML
- Manuels : `/ping`, `/army`, `/mineur`, `/champ`, `/foret`, `/village`, `/eleveur` selon la feature
- Cas critiques : suppression de coffres → zones désactivées, vérification persistance et redémarrage Paper pour valider la sauvegarde

## Modules clés
- `MinePlugin` : point d’entrée, enregistrement commandes/événements, orchestration des managers
- `Mineur`, `Agriculture`, `Foret`, `Eleveur` : zones automatisées + persistance YAML
- `Village` (+ sous-packages) : génération asynchrone, muraille, PNJ, batiments
- `Armure` : armure légendaire, gardiens, buffs
- Utilitaires : `TeleportUtils`, `Batiments`, autres helpers communs

## Notes Paper/Bukkit
- API Bukkit uniquement sur le thread principal pour les actions jeu/mondes
- Scheduler async pour les tâches longues, IO, génération de structures, calculs lourds
- Agir uniquement dans des chunks chargés (pas de force-load gratuit)
- Persister uniquement ce qui est nécessaire dans `plugins/MinePlugin/` en YAML
- Toujours décrire le plan avant toute action destructive ou qui touche plusieurs systèmes

## Ressources utiles
- `README.md` : documentation des commandes et du gameplay
- `docs/IDEES.md` : cahier des charges, architecture gameplay
- `target/surefire-reports/` : diagnostics en cas d’échec Maven
- Journal Maven (`mvn -q package` / `mvn -q test`) pour tracer les régressions

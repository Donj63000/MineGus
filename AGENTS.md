# Repository Guidelines

## Conduite des messages
- Traiter chaque message en plusieurs etapes (planification puis execution) pour garantir que meme des prompts tres longs soient executes correctement et integralement.

## Project Structure & Modules
- `src/main/java/org/example/`: gameplay code; `MinePlugin` bootstraps modules (`Mineur`, `Agriculture`, `Foret`, `Village`, `Eleveur`, `Armure`, `MerchantManager`, helpers like `TeleportUtils`, `Golem`).
- `src/main/resources/`: `plugin.yml`, default `config.yml`, `marchand.yaml`.
- `src/test/java/`: JUnit 5 + MockBukkit tests (e.g., `InventoryRouterTest`, `QuarryIteratorTest`, `MerchantConfigMaterialTest`); mirror package paths for new tests.
- `docs/IDEES.md`: design notes for village structures; keep for context, not specs.
- `registries/registry_key_class_relation.json`: registry-class map used by tests; extend if new Bukkit registries are mocked.
- `target/`: build outputs only; never edit or commit.

## Build, Test, and Development Commands
- Build: `mvn -q package` (outputs `target/MineGus-<version>.jar`).
- Tests only: `mvn -q test` (CI runs this via `.github/workflows/maven.yml`).
- Fast rebuild when tests unchanged: `mvn -q -DskipTests package`.
- Local run: copy the jar to a Paper 1.21.4 server `plugins/` folder, ensure Java 17 (`JAVA_HOME`) then start the server.
- Avant push/PR: exécuter `mvn -q package` et corriger tout échec; le code doit compiler et les tests doivent passer.

## Coding Style & Naming Conventions
- Java 17, 4-space indent, braces on the same line. Classes `PascalCase`; methods/fields `camelCase`; constants `UPPER_SNAKE_CASE`.
- Keep user-facing strings and doc in French to match existing content.
- Follow existing module boundaries: logic stays inside its feature class; use helpers (`TeleportUtils`, `MiningLoop`, `VillageEntityManager`) instead of duplicating routines.

## Testing Guidelines
- Prefer MockBukkit for behavior tests; keep tests headless and fast.
- Name tests `*Test` and place alongside the mirrored package path.
- For new registries or materials, extend `registries/registry_key_class_relation.json` so lookups resolve in tests.
- When altering async logic (loops, schedulers), add assertions covering tick timing and cleanup to prevent main-thread stalls.
- Chaque ajout de fonctionnalité ou correction doit venir avec des tests unitaires couvrant les cas principaux; ne pas introduire de logique sans couverture.

## Debugging & Logging
- Ajouter des logs de debug autour des chemins critiques ou en cas de scénario incertain; ils doivent apparaître dans la console pour faciliter le diagnostic (niveau DEBUG/INFO adapté).

## Commit & Pull Request Guidelines
- Commits: French, present tense, concise (ex: `Ajoute collecte du champ`, `Corrige spawn des golems`). Do not touch `target/*.jar`.
- Branches: `feat/...`, `fix/...`, or `chore/...`.
- PRs: describe objective, key changes, build/test result (`mvn -q test` or `mvn -q package`), and manual steps run in-game if relevant (`/ping`, `/army`, `/mineur` scenario, etc.). Include screenshots or logs when UI/behavior changes.

## Safety & Server Practices
- Keep configuration defaults under `src/main/resources/`; never overwrite a player's runtime files in `plugins/MinePlugin/`.
- Avoid heavy work on the main thread; schedule async tasks and operate only on loaded chunks.
- Persist only necessary YAML state; avoid extra disk IO or new files outside `plugins/MinePlugin/`.

# Repository Guidelines

## Project Structure & Module Organization
- `src/main/java/`: plugin source (commands, tasks, utilities).
- `src/main/resources/`: `plugin.yml`, default `config.yml`.
- `docs/IDEES.md`: ideas and notes.
- `src/test/java/`: tests (JUnit 5) if present.
- `target/`: build artifacts (do not commit or edit).

## Build, Test, and Run Locally
- Build: `mvn -q package` → `target/MineGus-<version>.jar`.
- Tests: `mvn -q test` (if tests exist under `src/test/java/`).
- Local run: copy the JAR to your Paper server `plugins/` directory and start the server.
- Environment: Java 17; Paper 1.20.x API.

## Coding Style & Naming Conventions
- Indentation: 4 spaces; braces on the same line.
- Naming: `PascalCase` classes, `camelCase` methods/fields, `UPPER_SNAKE_CASE` constants.
- Keep code simple and aligned with existing patterns; avoid one-letter identifiers.
- Never modify compiled artifacts (`target/*.jar`). Apply maintainer-provided code exactly as given.

## Agent-Specific Instructions (Minecraft/Paper)
- Declare commands in `src/main/resources/plugin.yml` (name, description, permissions).
- Implement `CommandExecutor`/`TabExecutor` in `src/main/java/` and register in `onEnable()`.
- Use Bukkit scheduler: run Bukkit API on main thread (`runTask`/`runTaskTimer`); offload heavy/IO work with async tasks (never call Bukkit API off-thread).
- Avoid chunk thrashing: operate in loaded chunks; don’t force-load unless necessary; mirror existing safety checks.
- Persist only needed data (YAML under `plugins/MinePlugin/`); avoid blocking IO on the main thread.
- After each change, run `mvn -q package`. Do not edit `MinePlugin.jar` or any file in `target/`.

## Testing Guidelines (Manual)
- Verify `/ping` and `/army` (spawn, duration, cleanup).
- Walk through flows for `/mineur`, `/champ`, `/foret`, `/village`, `/eleveur`.
- Restart server to confirm persistence and restoration; removing all chests should stop zones.

## Commit & Pull Request Guidelines
- Commits: French, present tense, concise (e.g., « Ajoute collecte du champ », « Corrige orientation des escaliers »).
- PRs include: goal, key changes, build result (`mvn -q package`), manual test steps, and screenshots if visuals change.

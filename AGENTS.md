# Repository Guidelines

## Project Structure & Module Organization
Keep gameplay logic, listeners, and utilities inside `src/main/java/`. Handle Paper descriptors such as `plugin.yml` and `config.yml` under `src/main/resources/`. Manual notes belong in `docs/IDEES.md`. Add automated checks to `src/test/java/` using JUnit 5. Build output is written to `target/`; do not edit or commit files from that directory.

## Build, Test, and Development Commands
Use `mvn -q package` to compile and assemble `target/MineGus-<version>.jar`. Run `mvn -q test` to execute the current test suite. If dependencies appear stale or the build fails, reset with `mvn -q clean package`. Deploy locally by copying the generated jar into your Paper server `plugins/` folder, then restart the server.

## Coding Style & Naming Conventions
Target Java 17 and keep indentation at four spaces. Place opening braces on the same line as declarations. Follow `PascalCase` for classes, `camelCase` for methods and fields, and `UPPER_SNAKE_CASE` for constants. Reflect nearby patterns, avoid one-letter identifiers, and keep refactors scoped to the requested change set. When maintainers supply snippets, apply them verbatim.

## Testing Guidelines
Write JUnit 5 tests in `src/test/java/`, naming each class after the feature under test (for example `ArmyCommandTest`). Prefer focused unit tests; when automation is unavailable, manually verify `/ping`, `/army`, `/mineur`, `/champ`, `/foret`, `/village`, and `/eleveur`. Always restart the Paper server after deploying to confirm persistence and ensure that removing every chest disables its zone.

## Commit & Pull Request Guidelines
Author commits in concise French, present tense (e.g., `Ajoute collecte du champ`, `Corrige orientation des escaliers`). Pull requests should explain the goal, summarize key modifications, report `mvn -q package` results, and document manual test steps. Attach screenshots for visual updates and reference related issues when applicable.

## Agent-Specific Notes
Read `instructions.txt` at startup and before any major edit; it supersedes this guide. Interact with Bukkit APIs on the main thread and schedule heavy or blocking work asynchronously. Keep operations within already loaded chunks and persist only essential YAML under `plugins/MinePlugin/`. Before destructive actions, outline the planned change and confirm intent when unsure.

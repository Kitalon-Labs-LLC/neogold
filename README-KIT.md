# NeoGold starter kit

Unzip into an empty Git repository, commit, and start Claude Code in the root.

| Path | What it is |
|---|---|
| `CLAUDE.md` | Rules Claude Code reads automatically in every session |
| `docs/` | The 15 design documents; `docs/api/openapi.yaml` is the API source of truth |
| `docs/ci-templates/` | Validated CI workflows; work packages enable them as their Gradle tasks appear |
| `.claude/commands/ng-p00.md` to `ng-p22.md` | One slash command per work package: run `/ng-p00`, then `/ng-p01`, and so on |
| `.claude/agents/` | `safety-reviewer` and `privacy-security-reviewer`, used before every PR |
| `tools/` | Release gates and checks (tested): sign-off, manifest, network config, 16 KB, coverage, triage reference |
| `backend/deploy/` | Validated server files: Compose, Caddy, deploy, backup, bootstrap, admin wrapper |

Start with `/ng-p00`. The order and dependencies are in `docs/14_Build_Plan.md`.
Every command plans first and waits for your approval before writing code.

# Claude_builds

A collection of independent projects built with Claude. Each one lives in its own folder with its own
README, build, tests and CI workflow. Nothing is shared between them.

| Project | Folder | What it is |
|---|---|---|
| **JustRight** | [`composition-coach/`](composition-coach/README.md) | Android camera that frames for you and coaches you after the shot, all on-device. [Download the latest APK](https://github.com/jkrek17/Claude_builds/releases/tag/release-latest) |
| **Fantasy Coach** | [`fantasy/`](fantasy/README.md) | Read-only Yahoo Fantasy Football MCP server for Claude: league history, waiver, trade and lineup advice |

## Repo layout

Only what GitHub or Claude Code require to be at the root lives here:

- `.github/workflows/<project>.yml`: one workflow per project, triggered only by changes to that
  project's folder.
- `.mcp.json` and `.claude/settings.json`: let Claude Code sessions on this repo load the Fantasy Coach
  MCP server.
- `CLAUDE.md`: conventions for adding and changing projects.

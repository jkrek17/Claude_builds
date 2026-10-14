# Claude_builds

This repo is a collection of independent projects, one per top-level folder (`composition-coach/`,
`fantasy/`, ...).

- **Each project is self-contained in its own folder**: source, build files, tests, docs, scripts and
  its own `.gitignore`. Don't put project files at the repo root, and don't share code or build config
  between projects.
- The root holds only what tools require there: `README.md` (an index of the projects; add a row for a
  new one), this file, `.gitignore` (repo-wide ignores), `.github/workflows/`, `.mcp.json` and `.claude/`.
- **One CI workflow per project**, named `.github/workflows/<folder>.yml`, using `paths:` filters on that
  folder plus the workflow file, and `defaults.run.working-directory: <folder>`. A change to one project
  must not trigger another project's CI or releases.
- A project's own `CLAUDE.md` (for example `fantasy/CLAUDE.md`) adds rules for that project.
- Start a new project in a new folder, on its own branch and PR.

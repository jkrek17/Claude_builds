#!/bin/sh
# Starts the fantasy-coach MCP server, installing it into fantasy/.venv first if needed.
# Cloud sessions start from a fresh checkout, so the first start of each session installs (~20s).
# Only the server may write to stdout (it carries the MCP protocol); install output goes to stderr.
set -e
DIR="$(cd "$(dirname "$0")/.." && pwd)"
VENV="$DIR/.venv"
if [ ! -x "$VENV/bin/fantasy-coach" ]; then
  python3 -m venv "$VENV" >&2
  "$VENV/bin/pip" install --quiet --disable-pip-version-check -e "$DIR" >&2
fi
exec "$VENV/bin/fantasy-coach" serve

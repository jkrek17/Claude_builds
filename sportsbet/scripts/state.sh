#!/usr/bin/env bash
# Loop state lives on an orphan branch, checked out as a git worktree next to the main checkout.
#   state.sh open DIR           check out the state branch into DIR (creates an empty orphan branch locally if none exists)
#   state.sh save DIR MESSAGE   commit everything in DIR and push; no commit when nothing changed; never forces
set -euo pipefail
BRANCH="${STATE_BRANCH:-sportsbet-data}"
cmd="$1"; dir="$2"
case "$cmd" in
  open)
    rc=0; git ls-remote --exit-code --heads origin "$BRANCH" >/dev/null || rc=$?
    case "$rc" in
      0) git fetch --no-tags --depth=1 origin "$BRANCH"
         git worktree add -B "$BRANCH" "$dir" FETCH_HEAD ;;
      2) git worktree add --orphan -b "$BRANCH" "$dir" ;;   # first run: the branch does not exist yet
      *) echo "cannot reach origin (git ls-remote exit $rc)" >&2; exit "$rc" ;;
    esac ;;
  save)
    git -C "$dir" add -A
    if git -C "$dir" diff --cached --quiet; then echo "state unchanged; nothing to commit"; exit 0; fi
    git -C "$dir" -c user.name="github-actions[bot]" \
        -c user.email="41898282+github-actions[bot]@users.noreply.github.com" commit -q -m "$3"
    git -C "$dir" push origin "HEAD:refs/heads/$BRANCH"     # plain push: a lost race fails instead of overwriting
    git -C "$dir" rev-parse HEAD ;;
  *) echo "usage: state.sh open DIR | save DIR MESSAGE" >&2; exit 64 ;;
esac

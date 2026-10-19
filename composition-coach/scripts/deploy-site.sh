#!/usr/bin/env bash
set -Eeuo pipefail

DOMAIN="${1:-sceneright.wxmap.pro}"
if [[ ! "$DOMAIN" =~ ^([a-z0-9]([a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,63}$ ]]; then
  printf 'Use a lowercase domain name, without a scheme or path.\n' >&2; exit 2
fi
if [[ "$EUID" -ne 0 ]]; then printf 'Run with sudo: sudo bash scripts/deploy-site.sh %s\n' "$DOMAIN" >&2; exit 2; fi
for program in caddy systemctl python3 flock; do
  command -v "$program" >/dev/null || { printf 'Missing required program: %s\n' "$program" >&2; exit 2; }
done
CONFIG=/etc/caddy/Caddyfile
SNIPPET=/etc/caddy/sceneright.caddy
BASE=/var/www/sceneright
SOURCE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../brand/site" && pwd)"
[[ -s "$SOURCE/index.html" ]] || { printf 'Site files are missing.\n' >&2; exit 2; }
systemctl is-active --quiet caddy || { printf 'A running host Caddy service was not found. For Docker Caddy, inspect the container mounts first. No changes made.\n' >&2; exit 2; }
[[ -f "$CONFIG" && ! -L "$CONFIG" ]] || { printf 'Expected a regular /etc/caddy/Caddyfile. No changes made.\n' >&2; exit 2; }
EXEC_START="$(systemctl show caddy --property=ExecStart --value)"
[[ "$EXEC_START" == *"--config /etc/caddy/Caddyfile"* ]] || { printf 'The Caddy service uses another config path. Inspect its configuration before deployment. No changes made.\n' >&2; exit 2; }
exec 9>/run/lock/sceneright-deploy.lock
flock -n 9 || { printf 'Another SceneRight deployment is running.\n' >&2; exit 2; }
if [[ -e "$SNIPPET" ]] && ! grep -Fq '# Managed by SceneRight deploy.sh' "$SNIPPET"; then
  printf 'The SceneRight Caddy snippet already exists and is not managed by this script. No changes made.\n' >&2; exit 2
fi
if grep -Fq "$DOMAIN" "$CONFIG"; then
  printf 'The chosen domain already appears directly in the Caddyfile. Review it before adding a separate site. No changes made.\n' >&2; exit 2
fi
caddy validate --config "$CONFIG" --adapter caddyfile
STAMP="$(date -u +%Y%m%dT%H%M%SZ)-$$"
BACKUP="/var/backups/sceneright/$STAMP"
RELEASE="$BASE/releases/$STAMP"
install -d -m 700 "$BACKUP"
cp -p -- "$CONFIG" "$BACKUP/Caddyfile"
OLD_SNIPPET=0
if [[ -e "$SNIPPET" ]]; then cp -p -- "$SNIPPET" "$BACKUP/site.caddy"; OLD_SNIPPET=1; fi
OLD_CURRENT=''
if [[ -L "$BASE/current" ]]; then OLD_CURRENT="$(readlink -- "$BASE/current")";
elif [[ -e "$BASE/current" ]]; then printf 'The existing current path is not a symlink. No site changes made.\n' >&2; exit 2; fi

rollback() {
  local code=$?
  trap - ERR INT TERM
  set +e
  cp -p -- "$BACKUP/Caddyfile" "$CONFIG"
  if [[ "$OLD_SNIPPET" == 1 ]]; then cp -p -- "$BACKUP/site.caddy" "$SNIPPET"; else rm -f -- "$SNIPPET"; fi
  if [[ -n "$OLD_CURRENT" ]]; then
    ln -s -- "$OLD_CURRENT" "$BASE/rollback-$STAMP"
    mv -Tf -- "$BASE/rollback-$STAMP" "$BASE/current"
  else rm -f -- "$BASE/current"; fi
  caddy validate --config "$CONFIG" --adapter caddyfile && systemctl reload caddy
  printf 'Deployment failed. Previous files and configuration restored. Backup: %s\n' "$BACKUP" >&2
  exit "$code"
}
trap rollback ERR
trap 'false' INT TERM
install -d -m 755 "$BASE" "$BASE/releases" "$RELEASE"
python3 - "$SOURCE" "$RELEASE" "$DOMAIN" <<'PY'
from pathlib import Path
import shutil,sys,re
source,destination,domain=Path(sys.argv[1]),Path(sys.argv[2]),sys.argv[3]
for p in source.rglob('*'):
    if p.is_symlink(): raise SystemExit('Unexpected symlink in site assets')
    if p.is_file() and p.suffix.lower() in {'.html','.css','.js','.svg','.png','.woff','.woff2','.txt','.ico','.jpg','.webp'}:
        out=destination/p.relative_to(source);out.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,out)
p=destination/'index.html'
s=p.read_text()
s=re.sub(r'(<meta property="og:image" content=")[^"]*(">)',lambda m:m[1]+f'https://{domain}/assets/social-preview.png'+m[2],s)
p.write_text(s)
for p in destination.rglob('*'):p.chmod(0o755 if p.is_dir() else 0o644)
PY
ln -s -- "$RELEASE" "$BASE/current-$STAMP"
mv -Tf -- "$BASE/current-$STAMP" "$BASE/current"
printf '# Managed by SceneRight deploy.sh\n%s {\n    root * /var/www/sceneright/current\n    encode zstd gzip\n    file_server\n}\n' "$DOMAIN" > "$SNIPPET"
chmod 644 "$SNIPPET"
if ! grep -Eq '^import[[:space:]]+/etc/caddy/sceneright\.caddy[[:space:]]*$' "$CONFIG"; then
  printf '\nimport /etc/caddy/sceneright.caddy\n' >> "$CONFIG"
fi
caddy validate --config "$CONFIG" --adapter caddyfile
systemctl reload caddy
trap - ERR INT TERM
printf 'Installed release %s. Configuration backup: %s\n' "$STAMP" "$BACKUP"
if command -v curl >/dev/null && curl --fail --silent --show-error --max-time 20 "https://$DOMAIN/" -o /dev/null; then
  printf 'HTTPS check passed: https://%s\n' "$DOMAIN"
else
  printf 'HTTPS check is pending. Check DNS and Caddy certificate issuance for https://%s\n' "$DOMAIN"
fi

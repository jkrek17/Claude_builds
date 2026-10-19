# SceneRight — GitHub to VPS

Planned site address: `https://sceneright.wxmap.pro`.

The site lives in this repository under `composition-coach/brand/site/`. No DNS record has been created and nothing has been deployed to the VPS yet.

## Repository layout

- `brand/identity/`: editable logo masters, app icons, Play feature graphic, tokens, and brand board.
- `brand/site/`: static landing page with local assets.
- `brand/guidelines/`: brand guide and naming decision.
- `brand/source/build_identity.py`: identity generator.
- `scripts/deploy-site.sh`: Caddy deployment helper.

## First deployment

Create the repository, upload the package contents, and ensure the subdomain's IPv4 and any IPv6 records point at the VPS. Then run in the VPS terminal:

```bash
git clone https://github.com/jkrek17/Claude_builds.git
cd Claude_builds/composition-coach
sudo bash scripts/deploy-site.sh sceneright.wxmap.pro
```

A private repository needs the VPS's existing GitHub authentication. Credentials must not be added to the project.

The script supports an existing host Caddy systemd service using `/etc/caddy/Caddyfile`. It does not install or replace Caddy. It stops before site changes if the actual setup differs, including Docker-hosted Caddy, so the real mounts and configuration can be inspected first.

It copies only web assets into `/var/www/sceneright/releases/`, switches an atomic `current` symlink, updates the social image origin to the chosen hostname, adds `/etc/caddy/sceneright.caddy`, validates the combined configuration, and reloads Caddy. Existing site blocks remain in the main Caddyfile. Previous releases remain available. Backups go into `/var/backups/sceneright/`.

Configuration validation or reload failures trigger restoration of the previous configuration and site pointer. The script reports an unsuccessful HTTPS check as pending, rather than declaring a successful public verification.

## Later updates

From the repository checkout on the VPS:

```bash
git pull --ff-only
sudo bash scripts/deploy-site.sh sceneright.wxmap.pro
```

The domain is an argument; another approved hostname can be passed instead. The script does not create DNS records.

After the first deployment, verify the new site and its assets, both demo tabs, and the existing POD endpoint. The helper has not been run on the actual VPS. Its Caddy setup has not been inspected from this session.

The app itself lives beside the site in `composition-coach/`; its label, strings and release notes already carry the name.

Caddy references: https://caddyserver.com/docs/caddyfile/directives/import and https://caddyserver.com/docs/command-line

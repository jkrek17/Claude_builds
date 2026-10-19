#!/usr/bin/env bash
# Generates the Play upload key for Composition Coach and prints the four GitHub secrets to set.
# Run once, on your own machine, and keep the .jks file and passwords somewhere safe (password manager).
#
#   ./scripts/make-upload-key.sh
#
set -euo pipefail
KEYSTORE="${1:-composition-coach-upload.jks}"
ALIAS="upload"
if [ -f "$KEYSTORE" ]; then echo "Refusing to overwrite existing $KEYSTORE"; exit 1; fi
read -r -s -p "Choose a keystore password: " STORE_PASS; echo
read -r -s -p "Choose a key password (can be the same): " KEY_PASS; echo
keytool -genkeypair -v \
  -keystore "$KEYSTORE" -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
  -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Composition Coach, O=Composition Coach, C=US"
echo
echo "Add these four repository secrets (GitHub > Settings > Secrets and variables > Actions):"
echo
echo "  CC_RELEASE_KEYSTORE_BASE64  = (contents of $KEYSTORE.base64, printed below)"
echo "  CC_RELEASE_KEYSTORE_PASSWORD = the keystore password you chose"
echo "  CC_RELEASE_KEY_ALIAS        = $ALIAS"
echo "  CC_RELEASE_KEY_PASSWORD     = the key password you chose"
echo
base64 -w0 "$KEYSTORE" > "$KEYSTORE.base64" 2>/dev/null || base64 -i "$KEYSTORE" | tr -d '\n' > "$KEYSTORE.base64"
echo "Base64 written to $KEYSTORE.base64 (single line). Paste its contents as CC_RELEASE_KEYSTORE_BASE64."
echo "Then push any commit: the release job signs with this key and publishes release-latest and the AAB artifact."

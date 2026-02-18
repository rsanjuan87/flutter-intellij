#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: install-local-plugin.sh <plugins-dir> [plugin-zip]

Arguments:
  plugins-dir  Path to the IDE plugins directory
  plugin-zip   Path to the built plugin zip (default: build/distributions/flutter_intellij_plugin.zip)

Example:
  ./tool/install-local-plugin.sh "$HOME/Library/Application Support/JetBrains/IntelliJIdea2025.2/plugins"
  ./tool/install-local-plugin.sh "$HOME/Library/Application Support/Google/AndroidStudio2025.1/plugins" build/distributions/flutter_intellij_plugin.zip
EOF
}

#if [[ $# -lt 1 || $# -gt 2 ]]; then
#  usage
#  exit 1
#fi
AS_VERSION=$(ls "$HOME/Library/Application Support/Google" | grep -E 'AndroidStudio[0-9]+(\.[0-9]+)?' | sort -V | tail -n 1 | sed 's/AndroidStudio//')
PLUGINS_DIR="${1:-$HOME/Library/Application Support/Google/AndroidStudio$AS_VERSION/plugins}"
ZIP_PATH="${2:-build/distributions/flutter_intellij_plugin.zip}"

if [[ ! -d "$PLUGINS_DIR" ]]; then
  echo "Plugins directory not found: $PLUGINS_DIR" >&2
  exit 1
fi

if [[ ! -f "$ZIP_PATH" ]]; then
  echo "Plugin zip not found: $ZIP_PATH" >&2
  exit 1
fi

if ! command -v unzip >/dev/null 2>&1; then
  echo "unzip is required but not found in PATH." >&2
  exit 1
fi

PLUGIN_DIR_NAME=$(unzip -Z1 "$ZIP_PATH" | head -n 1 | cut -d/ -f1)
if [[ -z "$PLUGIN_DIR_NAME" ]]; then
  echo "Unable to determine plugin directory name from zip." >&2
  exit 1
fi

TARGET_DIR="$PLUGINS_DIR/$PLUGIN_DIR_NAME"
if [[ -d "$TARGET_DIR" ]]; then
  echo "Removing existing plugin directory: $TARGET_DIR"
  rm -rf "$TARGET_DIR"
fi

mkdir -p "$PLUGINS_DIR"
unzip -o "$ZIP_PATH" -d "$PLUGINS_DIR" >/dev/null

echo "Installed plugin to: $TARGET_DIR"
echo "Please 'Enter' to restart your IDE to apply the changes."
read -r
pkill studio; sleep 2; open -a "Android Studio"

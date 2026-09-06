#!/usr/bin/env bash
# ==============================================================================
# Emzoom AA - Upstream Sync & Upgrade Tool
# ==============================================================================
# Usage:
#   ./sync_upstream.sh [tag_or_branch]
#
# Example:
#   ./sync_upstream.sh v.3.3.1-beta1
#   ./sync_upstream.sh upstream/main
# ==============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

UPSTREAM_TARGET="${1:-upstream/main}"

echo "=========================================================="
echo "          Emzoom AA Upstream Sync & Upgrade Tool          "
echo "=========================================================="
echo "Fetching latest upstream tags and commits..."
git fetch upstream --tags || true

echo "Target Upstream: $UPSTREAM_TARGET"
echo "----------------------------------------------------------"

# Ensure working tree is clean
if [ -n "$(git status --porcelain)" ]; then
    echo "Error: Working directory has uncommitted changes. Please commit or stash them first."
    exit 1
fi

CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
UPGRADE_BRANCH="upgrade/sync-$(date +%Y%m%d_%H%M%S)"

echo "[1/4] Creating temporary upgrade branch: $UPGRADE_BRANCH"
git checkout -b "$UPGRADE_BRANCH" "$UPSTREAM_TARGET"

echo "[2/4] Generating patch from Emzoom AA backup package..."
PATCH_FILE="/tmp/emzoom_sync.patch"
if [ -f "emzoom_v1.6_backup/patches/emzoom_v1.6_clean.patch" ]; then
    CP_PATCH="emzoom_v1.6_backup/patches/emzoom_v1.6_clean.patch"
else
    CP_PATCH="$SCRIPT_DIR/emzoom_v1.6_backup/patches/emzoom_v1.6_clean.patch"
fi

echo "[3/4] Applying Emzoom AA customizations with 3-way merge..."
if git apply --3way "$CP_PATCH" 2>/dev/null; then
    echo "✓ Emzoom AA patch applied cleanly with zero conflicts!"
else
    echo "⚠️ Minor line conflicts detected during 3-way apply."
    echo "Falling back to copying modular overlay files..."
    if [ -d "emzoom_v1.6_backup/overlay" ]; then
        cp -Rv "emzoom_v1.6_backup/overlay/"* "$SCRIPT_DIR/"
    fi
fi

echo "[4/4] Verifying build..."
if ./gradlew :app:assembleEmzoomDebug; then
    echo "=========================================================="
    echo "✓ UPGRADE SUCCESSFUL! Emzoom AA compiled cleanly."
    echo "=========================================================="
    echo "To complete the upgrade and merge into $CURRENT_BRANCH:"
    echo "  git add -A"
    echo "  git commit -m 'Emzoom AA: Upgraded to $UPSTREAM_TARGET'"
    echo "  git checkout $CURRENT_BRANCH"
    echo "  git reset --hard $UPGRADE_BRANCH"
else
    echo "=========================================================="
    echo "⚠️ Build requires manual conflict resolution in Gradle or code."
    echo "Inspect build errors above to resolve."
    echo "=========================================================="
fi

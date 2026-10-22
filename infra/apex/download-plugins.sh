#!/usr/bin/env bash
# Downloads the exact plugin jars the Apex server needs into infra/apex/plugins/.
# Upload everything in that folder to the server's plugins/ directory.
set -euo pipefail

cd "$(dirname "$0")"
mkdir -p plugins

plugins=(
  "ViaVersion-5.12.1.jar https://cdn.modrinth.com/data/P1OZGk5p/versions/5WgnasGG/ViaVersion-5.12.1.jar b2e5c03689e2265410613a928640076cfcd576e7a3dc7ca1276a76dbe624f2f5467348c3b3eaf8b5987ed7662afb93cc5cb9373035f4c5512884116e4bb6add9"
  "ViaBackwards-5.12.1.jar https://cdn.modrinth.com/data/NpvuJQoq/versions/dWw67APC/ViaBackwards-5.12.1.jar 65752dd113bf1266ef2c3b25a748e79e9808bb67682a3f5fbcf3e2b097d76182786d0871562175af8d491bde7ec6addfeaf97fdb23987cb11b829b0c22f6e5ff"
  "packetevents-spigot-2.14.0.jar https://cdn.modrinth.com/data/HYKaKraK/versions/m78nFxYg/packetevents-spigot-2.14.0.jar 18a942cb55f64783a63b28deea2cebbbe782e5d6a1089f6700430e183bfbce0a45398a640ed0dff4361b55190dcf47017f2ca7788f4a5c8c9efad1f549a63fe3"
  "AuthMe-6.0.1-Paper.jar https://cdn.modrinth.com/data/9js4IEHC/versions/fPuH4uYs/AuthMe-6.0.1-Paper.jar 832eb1570f1887493dc09bd183360c6c890aafe24fbc635d36f7c3162c339f064e3a0e082519defe7e67569c1d78072f582cb49c14a24d125d4ece463b765271"
)

sha512() {
  if command -v sha512sum >/dev/null; then sha512sum "$1"; else shasum -a 512 "$1"; fi | cut -d' ' -f1
}

for entry in "${plugins[@]}"; do
  read -r name url hash <<<"$entry"
  curl -fsSL -o "plugins/$name" "$url"
  if [[ "$(sha512 "plugins/$name")" != "$hash" ]]; then
    echo "Checksum mismatch for $name" >&2
    rm -f "plugins/$name"
    exit 1
  fi
  echo "ok  $name"
done

#!/usr/bin/env bash
# Print this machine's LAN IPv4 address and the URLs to put in .env.
#
# Usage (from workstreams/backend):
#   bash scripts/lan-url.sh              # frontend on port 3000, API on 8080
#   bash scripts/lan-url.sh 4000 9090    # custom frontend / API ports
#
# The address is what a phone on the same Wi-Fi must use to reach this laptop,
# so it is the value APP_PUBLIC_BASE_URL needs for the QR code to work.

set -euo pipefail

FRONTEND_PORT="${1:-3000}"
API_PORT="${2:-8080}"

is_windows() {
  case "$(uname -s 2>/dev/null || echo unknown)" in
    MINGW*|MSYS*|CYGWIN*) return 0 ;;
    *) return 1 ;;
  esac
}

# Keep only routable private/public addresses.
filter_ip() {
  grep -Ev '^(127\.|169\.254\.|0\.0\.0\.0$)' || true
}

find_lan_ip() {
  local ip=""

  # --- Windows (Git Bash / MSYS) -----------------------------------------
  # Parse ipconfig.exe. The label is localized (IPv4 Address / IPv4-Adresse /
  # Adresse IPv4 ...), so match on the "IPv4" token only and pull out the
  # dotted quad, which is ASCII in every locale.
  if is_windows && command -v ipconfig.exe >/dev/null 2>&1; then
    ip="$(ipconfig.exe 2>/dev/null |
      tr -d '\r' |
      grep -i 'IPv4' |
      grep -Eo '([0-9]{1,3}\.){3}[0-9]{1,3}' |
      filter_ip |
      head -n 1)"
    [ -n "$ip" ] && { printf '%s\n' "$ip"; return 0; }
  fi

  # --- Linux -------------------------------------------------------------
  # Ask the routing table which source address reaches the internet.
  if command -v ip >/dev/null 2>&1; then
    ip="$(ip -4 route get 1.1.1.1 2>/dev/null |
      sed -n 's/.*src \([0-9.]*\).*/\1/p' | filter_ip | head -n 1)"
    [ -n "$ip" ] && { printf '%s\n' "$ip"; return 0; }
  fi

  # --- macOS / BSD -------------------------------------------------------
  if [ "$(uname -s 2>/dev/null)" = "Darwin" ] && command -v ipconfig >/dev/null 2>&1; then
    for iface in $(route -n get default 2>/dev/null | sed -n 's/.*interface: \(.*\)/\1/p') en0 en1; do
      ip="$(ipconfig getifaddr "$iface" 2>/dev/null | filter_ip | head -n 1)"
      [ -n "$ip" ] && { printf '%s\n' "$ip"; return 0; }
    done
  fi

  # --- Last resort -------------------------------------------------------
  if command -v hostname >/dev/null 2>&1; then
    ip="$(hostname -I 2>/dev/null | tr ' ' '\n' | filter_ip | head -n 1)"
    [ -n "$ip" ] && { printf '%s\n' "$ip"; return 0; }
  fi

  return 1
}

IP="$(find_lan_ip || true)"

if [ -z "${IP:-}" ]; then
  echo "Could not determine a LAN IPv4 address automatically." >&2
  echo "Look it up manually (ipconfig / ifconfig) and use the 192.168.x.x or 10.x.x.x address of your Wi-Fi adapter." >&2
  exit 1
fi

echo "LAN IPv4 address : ${IP}"
echo
echo "Put these in your .env:"
echo
echo "  APP_PUBLIC_BASE_URL=http://${IP}:${FRONTEND_PORT}"
echo "  APP_CORS_ALLOWED_ORIGINS=http://${IP}:${FRONTEND_PORT},http://localhost:${FRONTEND_PORT}"
echo
echo "Contribute URL encoded in the QR code:"
echo
echo "  http://${IP}:${FRONTEND_PORT}/contribute"
echo
echo "API base URL (reachable from phones on the same Wi-Fi):"
echo
echo "  http://${IP}:${API_PORT}/api"
echo
echo "Check that the laptop and the phones are on the same Wi-Fi network and"
echo "that the firewall allows inbound TCP on ports ${FRONTEND_PORT} and ${API_PORT}."

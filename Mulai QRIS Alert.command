#!/bin/bash
# macOS: klik dua kali. Linux: jalankan dari terminal dengan ./"Mulai QRIS Alert.command"
cd "$(dirname "$0")" || exit 1
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

NODE="node"
[ -x "./runtime/node" ] && NODE="./runtime/node"

if ! command -v "$NODE" >/dev/null 2>&1; then
  echo
  echo "  Node.js belum terpasang di komputer ini."
  echo "  Halaman unduhan Node.js akan dibuka. Pasang versi LTS, lalu jalankan file ini lagi."
  echo
  open "https://nodejs.org/id/download" 2>/dev/null || xdg-open "https://nodejs.org/id/download" 2>/dev/null
  read -n 1 -s -r -p "  Tekan tombol apa saja untuk menutup."
  exit 1
fi

"$NODE" server/server.js --open

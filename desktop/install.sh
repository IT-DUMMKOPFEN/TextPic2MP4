#!/usr/bin/env bash
# Text2Video – Installation (macOS Apple Silicon / Linux)
set -e
cd "$(dirname "$0")"

PY=""
for c in python3.13 python3.12 python3.11 python3.10 python3; do
  if command -v "$c" >/dev/null 2>&1 && "$c" -c "import tkinter" >/dev/null 2>&1; then
    PY="$c"; break
  fi
done

if [ -z "$PY" ]; then
  echo "❌ Python 3 mit tkinter wurde nicht gefunden."
  if [ "$(uname)" = "Darwin" ]; then
    echo "   macOS:  brew install python python-tk"
    echo "   (oder Python von https://www.python.org installieren – tkinter ist dabei)"
  else
    echo "   Debian/Ubuntu:  sudo apt install python3 python3-venv python3-tk"
    echo "   Fedora:         sudo dnf install python3 python3-tkinter"
    echo "   Arch:           sudo pacman -S python tk"
  fi
  exit 1
fi

echo "➜ Verwende $($PY --version)"
"$PY" -m venv .venv
.venv/bin/python -m pip install --upgrade pip >/dev/null
.venv/bin/python -m pip install -r requirements.txt

chmod +x start.sh start.command 2>/dev/null || true
echo
echo "✅ Installation fertig. Starten mit:  ./start.sh"
[ "$(uname)" = "Darwin" ] && echo "   (oder Doppelklick auf start.command)"

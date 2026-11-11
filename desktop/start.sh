#!/usr/bin/env bash
cd "$(dirname "$0")"
if [ ! -x .venv/bin/python ]; then
  echo "Noch nicht installiert – starte install.sh …"
  bash install.sh || exit 1
fi
exec .venv/bin/python app.py "$@"

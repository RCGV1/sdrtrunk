#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
VENV_DIR="${SDRTRUNK_TRANSCRIBER_VENV:-$REPO_DIR/.venv-transcriber}"
PYTHON_BIN="${SDRTRUNK_TRANSCRIBER_PYTHON:-python3.12}"
REQUIREMENTS_FILE="$SCRIPT_DIR/requirements.txt"
REQUIREMENTS_STAMP="$VENV_DIR/.requirements.stamp"

if [[ ! -x "$VENV_DIR/bin/python" ]]; then
  "$PYTHON_BIN" -m venv "$VENV_DIR"
  "$VENV_DIR/bin/python" -m pip install --upgrade pip
fi

if [[ ! -f "$REQUIREMENTS_STAMP" ]] || [[ "$REQUIREMENTS_FILE" -nt "$REQUIREMENTS_STAMP" ]]; then
  "$VENV_DIR/bin/python" -m pip install --upgrade pip
  "$VENV_DIR/bin/python" -m pip install -r "$REQUIREMENTS_FILE"
  touch "$REQUIREMENTS_STAMP"
fi

exec "$VENV_DIR/bin/python" "$SCRIPT_DIR/sdrtrunk_transcriber.py" "$@"

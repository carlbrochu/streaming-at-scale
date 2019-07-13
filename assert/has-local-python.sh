#!/bin/bash

# Strict mode, fail on any error
set -euo pipefail

HAS_PYTHON=$(command -v python || true)
if [ -z "$HAS_PYTHON" ]; then
    echo "python 2.7 not found"
    echo "please install it using your package manager, for example, on Ubuntu:"
    echo "  sudo apt install python"
    echo "or as described here:"
    echo "https://wiki.python.org/moin/BeginnersGuide/Download"
    exit 1
fi

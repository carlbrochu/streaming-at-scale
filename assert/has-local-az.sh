#!/bin/bash

# Strict mode, fail on any error
set -euo pipefail

HAS_AZ=$(command -v az || true)
if [ -z "$HAS_AZ" ]; then
    echo "AZ CLI not found"
    echo "please install it as described here:"
    echo "https://docs.microsoft.com/en-us/cli/azure/install-azure-cli-apt?view=azure-cli-latest"
    exit 1
fi

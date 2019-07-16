#!/bin/bash

# Strict mode, fail on any error
set -euo pipefail

source ../streaming/databricks/runners/assert-common.sh

source ../streaming/databricks/job/run-databricks-job.sh assert-delta true "$(cat <<JQ
  .notebook_task.base_parameters."secrets-scope" = "$DATABRICKS_SECRETS_SCOPE"
  | .notebook_task.base_parameters."delta-table" = "events_$PREFIX"
  | .notebook_task.base_parameters."stream-temp-table" = "assert_delta_$PREFIX"
JQ
)"

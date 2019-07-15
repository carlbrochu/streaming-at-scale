#!/bin/bash

# Strict mode, fail on any error
set -euo pipefail

source ../streaming/databricks/job/run-databricks-job.sh delta-assert-performance true "$(cat <<JQ
  .notebook_task.base_parameters."stream-temp-table" = "delta_assert_performance"
  | .notebook_task.base_parameters."delta-table" = "events_$PREFIX"
JQ
)"

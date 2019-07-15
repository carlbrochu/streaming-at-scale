#!/bin/bash

# Strict mode, fail on any error
set -euo pipefail

echo 'writing Databricks secrets'
databricks secrets put --scope "MAIN" --key "azuresql-pass" --string-value "$SQL_ADMIN_PASS"

source ../streaming/databricks/job/run-databricks-job.sh assert-azuresql true "$(cat <<JQ
  .notebook_task.base_parameters."azuresql-servername" = "$SQL_SERVER_NAME"
  | .notebook_task.base_parameters."azuresql-finaltable" = "$SQL_TABLE_NAME"
  | .notebook_task.base_parameters."stream-temp-table" = "assert_azuresql_$PREFIX"
JQ
)"

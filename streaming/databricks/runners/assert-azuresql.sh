#!/bin/bash

# Strict mode, fail on any error
set -euo pipefail

echo 'writing Databricks secrets'
databricks secrets put --scope "$DATABRICKS_SECRETS_SCOPE" --key "azuresql-pass" --string-value "$SQL_ADMIN_PASS"

unique_string=$(uuidgen | tr "-" "_")

source ../streaming/databricks/job/run-databricks-job.sh assert-azuresql true "$(cat <<JQ
  .libraries += [ { "maven": { "coordinates": "com.microsoft.azure:azure-sqldb-spark:1.0.2" } } ]
  | .notebook_task.base_parameters."secrets-scope" = "$DATABRICKS_SECRETS_SCOPE"
  | .notebook_task.base_parameters."azuresql-servername" = "$SQL_SERVER_NAME"
  | .notebook_task.base_parameters."azuresql-finaltable" = "$SQL_TABLE_NAME"
  | .notebook_task.base_parameters."delta-temp-table" = "tmp_delta_table$unique_string"
  | .notebook_task.base_parameters."stream-temp-table" = "assert_azuresql_$PREFIX"
JQ
)"

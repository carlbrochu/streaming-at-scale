#!/bin/bash

# Strict mode, fail on any error
set -euo pipefail

source ../streaming/databricks/job/run-databricks-job.sh assert-cosmosdb true "$(cat <<JQ
  .notebook_task.base_parameters."cosmosdb-endpoint" = "https://$COSMOSDB_SERVER_NAME.documents.azure.com:443"
  | .notebook_task.base_parameters."cosmosdb-database" = "$COSMOSDB_DATABASE_NAME"
  | .notebook_task.base_parameters."cosmosdb-collection" = "$COSMOSDB_COLLECTION_NAME"
  | .notebook_task.base_parameters."stream-temp-table" = "assert_cosmosdb_$PREFIX"
JQ
)"

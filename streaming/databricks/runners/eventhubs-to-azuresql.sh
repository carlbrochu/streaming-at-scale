#!/bin/bash

# Strict mode, fail on any error
set -euo pipefail

echo 'getting shared access key'
EVENTHUB_CS=$(az eventhubs namespace authorization-rule keys list -g $RESOURCE_GROUP --namespace-name $EVENTHUB_NAMESPACE --name Listen --query "primaryConnectionString" -o tsv)

echo 'writing Databricks secrets'
databricks secrets put --scope "$DATABRICKS_SECRETS_SCOPE" --key "azuresql-pass" --string-value "$SQL_ADMIN_PASS"
databricks secrets put --scope "$DATABRICKS_SECRETS_SCOPE" --key "eventhub-cs-in-read" --string-value "$EVENTHUB_CS;EntityPath=$EVENTHUB_NAME"

checkpoints_dir=dbfs:/streaming_at_scale/checkpoints/streaming-azuresql
echo "Deleting checkpoints directory $checkpoints_dir"
databricks fs rm -r "$checkpoints_dir"

../streaming/databricks/job/run-databricks-job.sh eventhubs-to-azuresql false "$(cat <<JQ
  .libraries += [ { "maven": { "coordinates": "com.microsoft.azure:azure-sqldb-spark:1.0.2" } } ]
  | .notebook_task.base_parameters."secrets-scope" = "$DATABRICKS_SECRETS_SCOPE"
  | .notebook_task.base_parameters."eventhub-secret-name" = "eventhub-cs-in-read"
  | .notebook_task.base_parameters."eventhub-consumergroup" = "$EVENTHUB_CG"
  | .notebook_task.base_parameters."eventhub-maxEventsPerTrigger" = "$DATABRICKS_MAXEVENTSPERTRIGGER"
  | .notebook_task.base_parameters."azuresql-servername" = "$SQL_SERVER_NAME"
  | .notebook_task.base_parameters."azuresql-finaltable" = "$SQL_TABLE_NAME"
  | .notebook_task.base_parameters."azuresql-etlstoredproc" = "$SQL_ETL_STORED_PROC"
  | .notebook_task.base_parameters."stream-temp-table" = "eventhubs_to_azuresql_$PREFIX"
  
JQ
)"

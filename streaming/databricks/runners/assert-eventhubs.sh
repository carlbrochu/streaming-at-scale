#!/bin/bash

# Strict mode, fail on any error
set -euo pipefail

echo 'getting shared access key'
EVENTHUB_CS=$(az eventhubs namespace authorization-rule keys list -g $RESOURCE_GROUP --namespace-name $EVENTHUB_NAMESPACE_OUT --name Listen --query "primaryConnectionString" -o tsv)

echo 'writing Databricks secrets'
databricks secrets put --scope "$DATABRICKS_SECRETS_SCOPE" --key "eventhub-cs-out-read" --string-value "$EVENTHUB_CS"

source ../streaming/databricks/job/run-databricks-job.sh assert-eventhubs true "$(cat <<JQ
  .notebook_task.base_parameters."secrets-scope" = "$DATABRICKS_SECRETS_SCOPE"
  | .notebook_task.base_parameters."eventhub-secret-name" = "eventhub-cs-out-read"
  | .notebook_task.base_parameters."eventhub-consumergroup" = "$EVENTHUB_CG"
  | .notebook_task.base_parameters."eventhub-maxEventsPerTrigger" = "$DATABRICKS_MAXEVENTSPERTRIGGER"
  | .notebook_task.base_parameters."stream-temp-table" = "assert_eventhubs_$PREFIX"
JQ
)"

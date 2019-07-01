#!/bin/bash

# Strict mode, fail on any error
set -euo pipefail

echo 'getting EH primary connection string'
EVENTHUB_CS=$(az eventhubs namespace authorization-rule keys list -g $RESOURCE_GROUP --namespace-name $EVENTHUB_NAMESPACE --name RootManageSharedAccessKey --query "primaryConnectionString" -o tsv)

echo "getting cosmosdb master key"
COSMOSDB_MASTER_KEY=$(az cosmosdb keys list -g $RESOURCE_GROUP -n $COSMOSDB_SERVER_NAME --query "primaryMasterKey" -o tsv)


echo 'writing Databricks secrets'
databricks secrets put --scope "MAIN" --key "cosmosdb-write-master-key" --string-value "$COSMOSDB_MASTER_KEY"
databricks secrets put --scope "MAIN" --key "event-hubs-read-connection-string" --string-value "$EVENTHUB_CS;EntityPath=$EVENTHUB_NAME"

echo 'importing Spark library'
# Cosmos DB must be imported as Uber JAR and not resolved through maven coordinates,
# see https://kb.databricks.com/data-sources/cosmosdb-connector-lib-conf.html
cosmosdb_spark_jar=azure-cosmosdb-spark_2.4.0_2.11-1.4.0-uber.jar
curl -O "http://central.maven.org/maven2/com/microsoft/azure/azure-cosmosdb-spark_2.4.0_2.11/1.4.0/$cosmosdb_spark_jar"
databricks fs cp --overwrite "$cosmosdb_spark_jar" "dbfs:/mnt/streaming-at-scale/$cosmosdb_spark_jar"

echo 'importing Databricks notebooks'
databricks workspace import_dir notebooks /Shared/streaming-at-scale --overwrite

echo 'running Databricks notebooks' | tee -a log.txt
cluster_def=$(
  cat <<JSON
{
  "spark_version": "5.4.x-scala2.11",
  "node_type_id": "$DATABRICKS_NODETYPE",
  "num_workers": $DATABRICKS_WORKERS,
  "spark_env_vars": {
    "PYSPARK_PYTHON": "/databricks/python3/bin/python3"
  }
}
JSON
)
# It is recommended to run each streaming job on a dedicated cluster.
for notebook in notebooks/*.scala; do

  notebook_name=$(basename $notebook .scala)
  notebook_path=/Shared/streaming-at-scale/$notebook_name

  echo "starting Databricks notebook job for $notebook"
  job=$(databricks jobs create --json "$(
    cat <<JSON
  {
    "name": "Sample $notebook_name",
    "new_cluster": $cluster_def,
    "libraries": [
        {
          "maven": {
            "coordinates": "com.microsoft.azure:azure-eventhubs-spark_2.11:2.3.12"
          }
        },
        {
          "jar": "dbfs:/mnt/streaming-at-scale/azure-cosmosdb-spark_2.4.0_2.11-1.4.0-uber.jar"
        }
    ],
    "timeout_seconds": 3600,
    "notebook_task": {
      "notebook_path": "$notebook_path",
      "base_parameters": {
        "cosmosdb-endpoint": "https://$COSMOSDB_SERVER_NAME.documents.azure.com:443",
        "cosmosdb-database": "$COSMOSDB_DATABASE_NAME",
        "cosmosdb-collection": "$COSMOSDB_COLLECTION_NAME",
        "eventhub-consumergroup": "$EVENTHUB_CG",
        "eventhub-maxEventsPerTrigger": "$DATABRICKS_MAXEVENTSPERTRIGGER"
      }
    }
  }
JSON
  )")
  job_id=$(echo $job | jq .job_id)

  run=$(databricks jobs run-now --job-id $job_id)

  # Echo job web page URL to task output to facilitate debugging
  run_id=$(echo $run | jq .run_id)
  databricks runs get --run-id "$run_id" | jq -r .run_page_url >>log.txt

done # for each notebook

echo 'removing downloaded .jar'
rm $cosmosdb_spark_jar

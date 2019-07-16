// Databricks notebook source
dbutils.widgets.text("secrets-scope", "MAIN", "Secrets scope")
dbutils.widgets.text("cosmosdb-endpoint", "https://MYACCOUNT.documents.azure.com", "Cosmos DB endpoint")
dbutils.widgets.text("cosmosdb-database", "streaming", "Cosmos DB database")
dbutils.widgets.text("cosmosdb-collection", "rawdata", "Cosmos DB collection")
dbutils.widgets.text("stream-temp-table", "stream_data", "Spark global temp table to pass stream data")

// COMMAND ----------

import java.util.UUID.randomUUID

// Configure the connection to your collection in Cosmos DB.
// Please refer to https://github.com/Azure/azure-cosmosdb-spark/wiki/Configuration-references
// for the description of the available configurations.
val cosmosDbConfig = Map(
  "Endpoint" -> dbutils.widgets.get("cosmosdb-endpoint"),
  "Masterkey" -> dbutils.secrets.get(scope = dbutils.widgets.get("secrets-scope"), key = "cosmosdb-write-master-key"),
  "Database" -> dbutils.widgets.get("cosmosdb-database"),
  "Collection" -> dbutils.widgets.get("cosmosdb-collection"),
  "ReadChangeFeed" -> "true",
  "ChangeFeedQueryName" -> ("Streaming Query from Cosmos DB Change Feed " + randomUUID().toString),
  "ChangeFeedStartFromTheBeginning" -> "true"
)

// COMMAND ----------

import com.microsoft.azure.cosmosdb.spark.streaming.CosmosDBSourceProvider

// Start reading change feed as a stream
spark
  .readStream
  .format(classOf[CosmosDBSourceProvider].getName)
  .options(cosmosDbConfig)
  .load()
  .withColumn("createdAt", 'createdAt.cast("timestamp"))
  .withColumn("enqueuedAt", 'enqueuedAt.cast("timestamp"))
  .withColumn("storedAt", '_ts.cast("timestamp"))
  .createOrReplaceGlobalTempView(dbutils.widgets.get("stream-temp-table"))

// COMMAND ----------

dbutils.notebook.exit("SUCCESS")

// Databricks notebook source
dbutils.widgets.text("cosmosdb-endpoint", "https://MYACCOUNT.documents.azure.com", "Cosmos DB endpoint")
dbutils.widgets.text("cosmosdb-database", "streaming", "Cosmos DB database")
dbutils.widgets.text("cosmosdb-collection", "rawdata", "Cosmos DB collection")
dbutils.widgets.text("stream-temp-table", "stream_data", "Spark global temp table to pass stream data")

// COMMAND ----------

val global_temp_db = spark.conf.get("spark.sql.globalTempDatabase")
val streamData = table(global_temp_db + "." + dbutils.widgets.get("stream-temp-table"))

// COMMAND ----------

// Configure the connection to your collection in Cosmos DB.
// Please refer to https://github.com/Azure/azure-cosmosdb-spark/wiki/Configuration-references
// for the description of the available configurations.
val cosmosDbConfig = Map(
  "Endpoint" -> dbutils.widgets.get("cosmosdb-endpoint"),
  "Masterkey" -> dbutils.secrets.get(scope = "MAIN", key = "cosmosdb-write-master-key"),
  "Database" -> dbutils.widgets.get("cosmosdb-database"),
  "Collection" -> dbutils.widgets.get("cosmosdb-collection")
)

// COMMAND ----------

import org.apache.spark.sql.functions._

// Convert Timestamp columns to Date type for Cosmos DB compatibility

var streamDataMutated = streamData
for (c <- streamDataMutated.schema.fields filter { _.dataType.isInstanceOf[org.apache.spark.sql.types.TimestampType] } map {_.name}) { 
  streamDataMutated = streamDataMutated.withColumn(c, date_format(col(c), "yyyy-MM-dd'T'HH:mm:ss.SSSX"))
}

// COMMAND ----------

import com.microsoft.azure.cosmosdb.spark.streaming.CosmosDBSinkProvider

val cosmosdb = streamDataMutated
  .writeStream
  .format(classOf[CosmosDBSinkProvider].getName)
  .option("checkpointLocation", "dbfs:/streaming_at_scale/checkpoints/streaming-cosmosdb")
  .outputMode("append")
  .options(cosmosDbConfig)
  .start()

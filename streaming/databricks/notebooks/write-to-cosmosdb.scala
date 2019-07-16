// Databricks notebook source
dbutils.widgets.text("secrets-scope", "MAIN", "Secrets scope")
dbutils.widgets.text("cosmosdb-endpoint", "https://MYACCOUNT.documents.azure.com", "Cosmos DB endpoint")
dbutils.widgets.text("cosmosdb-database", "streaming", "Cosmos DB database")
dbutils.widgets.text("cosmosdb-collection", "rawdata", "Cosmos DB collection")
dbutils.widgets.text("stream-temp-table", "stream_data", "Spark global temp table to pass stream data")

// COMMAND ----------

val global_temp_db = spark.conf.get("spark.sql.globalTempDatabase")
var streamData = table(global_temp_db + "." + dbutils.widgets.get("stream-temp-table"))

// COMMAND ----------

// Configure the connection to your collection in Cosmos DB.
// Please refer to https://github.com/Azure/azure-cosmosdb-spark/wiki/Configuration-references
// for the description of the available configurations.
val cosmosDbConfig = Map(
  "Endpoint" -> dbutils.widgets.get("cosmosdb-endpoint"),
  "Masterkey" -> dbutils.secrets.get(scope = dbutils.widgets.get("secrets-scope"), key = "cosmosdb-write-master-key"),
  "Database" -> dbutils.widgets.get("cosmosdb-database"),
  "Collection" -> dbutils.widgets.get("cosmosdb-collection")
)

// COMMAND ----------

import org.apache.spark.sql.functions._

// Convert Timestamp columns to Date type for Cosmos DB compatibility

for (c <- streamData.schema.fields filter { _.dataType.isInstanceOf[org.apache.spark.sql.types.TimestampType] } map {_.name}) { 
  streamData = streamData.withColumn(c, date_format(col(c), "yyyy-MM-dd'T'HH:mm:ss.SSSX"))
}

// COMMAND ----------

import org.apache.spark.sql.functions._
import java.time.Instant
import java.sql.Timestamp

if (! streamData.columns.contains("processedAt")) {
  streamData = streamData
    .withColumn("processedAt", lit(new Timestamp(Instant.now)))
}

// COMMAND ----------

import com.microsoft.azure.cosmosdb.spark.streaming.CosmosDBSinkProvider

streamData
  .writeStream
  .format(classOf[CosmosDBSinkProvider].getName)
  .option("checkpointLocation", "dbfs:/streaming_at_scale/checkpoints/streaming-cosmosdb")
  .outputMode("append")
  .options(cosmosDbConfig)
  .start()

// Databricks notebook source
dbutils.widgets.text("secrets-scope", "MAIN", "Secrets scope")
dbutils.widgets.text("eventhub-secret-name", "eventhub-cs-in-read", "Event Hubs connection string key name in secret scope")
dbutils.widgets.text("eventhub-consumergroup", "delta", "Event Hubs consumer group")
dbutils.widgets.text("eventhub-maxEventsPerTrigger", "1000", "Event Hubs max events per trigger")
dbutils.widgets.text("stream-temp-table", "stream_data", "Spark global temp table to pass stream data")

// COMMAND ----------

import org.apache.spark.eventhubs.{ EventHubsConf, EventPosition }

val eventHubsConf = EventHubsConf(dbutils.secrets.get(scope = dbutils.widgets.get("secrets-scope"), key = dbutils.widgets.get("eventhub-secret-name")))
  .setConsumerGroup(dbutils.widgets.get("eventhub-consumergroup"))
  .setStartingPosition(EventPosition.fromStartOfStream)
  .setMaxEventsPerTrigger(dbutils.widgets.get("eventhub-maxEventsPerTrigger").toLong)

val eventhubs = spark.readStream
  .format("eventhubs")
  .options(eventHubsConf.toMap)
  .load()

// COMMAND ----------

import org.apache.spark.sql.types._
import org.apache.spark.sql.functions._
val schema = StructType(
  StructField("eventId", StringType) ::
  StructField("complexData", StructType((1 to 22).map(i => StructField(s"moreData$i", DoubleType)))) ::
  StructField("value", StringType) ::
  StructField("type", StringType) ::
  StructField("deviceId", StringType) ::
  StructField("createdAt", TimestampType) :: Nil)

val streamData =
eventhubs
  .select(from_json(decode($"body", "UTF-8"), schema).as("eventData"), $"*")

// When consuming from the output of eventhubs-streamanalytics-eventhubs pipeline, 'enqueuedAt' will haven been
// set when reading from the first eventhub, and the enqueued timestamp of the second eventhub is then the 'storedAt' time
val enqueuedTimeColumnName = if (streamData.columns.contains("enqueuedAt")) "storedAt" else "enqueuedAt"

streamData
  .select($"eventData.*", $"offset", $"sequenceNumber", $"publisher", $"partitionKey", $"enqueuedTime".as(enqueuedTimeColumnName)) 
  .createOrReplaceGlobalTempView(dbutils.widgets.get("stream-temp-table"))

// COMMAND ----------

dbutils.notebook.exit("SUCCESS")

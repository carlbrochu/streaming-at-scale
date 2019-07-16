// Databricks notebook source
dbutils.widgets.text("secrets-scope", "MAIN", "Secrets scope")
dbutils.widgets.text("storage-account", "ADLSGEN2ACCOUNTNAME", "ADLS Gen2 storage account name")
dbutils.widgets.text("delta-table", "streaming_events", "Delta table to store events (will be dropped if it exists)")
dbutils.widgets.text("stream-temp-table", "stream_data", "Spark global temp table to pass stream data")

// COMMAND ----------

val global_temp_db = spark.conf.get("spark.sql.globalTempDatabase")
var streamData = table(global_temp_db + "." + dbutils.widgets.get("stream-temp-table"))

// COMMAND ----------

import org.apache.spark.sql.functions._
import java.time.Instant
import java.sql.Timestamp

if (! streamData.columns.contains("processedAt")) {
  streamData = streamData
    .withColumn("processedAt", lit(Timestamp.from(Instant.now)))
}

// COMMAND ----------

val gen2account = dbutils.widgets.get("storage-account")
spark.conf.set(
  s"fs.azure.account.key.$gen2account.dfs.core.windows.net",
  dbutils.secrets.get(scope = dbutils.widgets.get("secrets-scope"), key = "storage-account-key"))
spark.conf.set("fs.azure.createRemoteFileSystemDuringInitialization", "true")
dbutils.fs.ls(s"abfss://streamingatscale@$gen2account.dfs.core.windows.net/")
spark.conf.set("fs.azure.createRemoteFileSystemDuringInitialization", "false")

// COMMAND ----------

sql("DROP TABLE IF EXISTS `" + dbutils.widgets.get("delta-table") + "`")

// COMMAND ----------

// You can also use a path instead of a table, see https://docs.azuredatabricks.net/delta/delta-streaming.html#append-mode
streamData
  .withColumn("storedAt", current_timestamp)
  .writeStream
  .outputMode("append")
  .option("checkpointLocation", "dbfs:/streaming_at_scale/checkpoints/streaming-delta/" + dbutils.widgets.get("delta-table"))
  .format("delta")
  .option("path", s"abfss://streamingatscale@$gen2account.dfs.core.windows.net/" + dbutils.widgets.get("delta-table"))
  .table(dbutils.widgets.get("delta-table"))

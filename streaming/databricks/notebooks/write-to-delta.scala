// Databricks notebook source
dbutils.widgets.text("storage-account", "ADLSGEN2ACCOUNTNAME", "ADLS Gen2 storage account name")
dbutils.widgets.text("stream-temp-table", "stream_data", "Spark global temp table to pass stream data")

// COMMAND ----------

val global_temp_db = spark.conf.get("spark.sql.globalTempDatabase")
val streamData = table(global_temp_db + "." + dbutils.widgets.get("stream-temp-table"))

// COMMAND ----------

val gen2account = dbutils.widgets.get("storage-account")
spark.conf.set(
  s"fs.azure.account.key.$gen2account.dfs.core.windows.net",
  dbutils.secrets.get(scope = "MAIN", key = "storage-account-key"))
spark.conf.set("fs.azure.createRemoteFileSystemDuringInitialization", "true")
dbutils.fs.ls(s"abfss://databricks@$gen2account.dfs.core.windows.net/")
spark.conf.set("fs.azure.createRemoteFileSystemDuringInitialization", "false")

// COMMAND ----------

// You can also use a path instead of a table, see https://docs.azuredatabricks.net/delta/delta-streaming.html#append-mode
streamData.writeStream
  .outputMode("append")
  .option("checkpointLocation", "dbfs:/checkpoints/streaming-delta")
  .format("delta")
  .option("path", s"abfss://databricks@$gen2account.dfs.core.windows.net/stream_scale_events")
  .table("stream_scale_events")

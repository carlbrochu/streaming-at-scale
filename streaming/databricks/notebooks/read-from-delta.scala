// Databricks notebook source
dbutils.widgets.text("secrets-scope", "MAIN", "Secrets scope")
dbutils.widgets.text("delta-table", "streaming_events", "Delta table containing events")
dbutils.widgets.text("stream-temp-table", "stream_data", "Spark global temp table to pass stream data")

// COMMAND ----------

spark.conf.set("fs.azure.account.key", dbutils.secrets.get(scope = dbutils.widgets.get("secrets-scope"), key = "storage-account-key"))

// COMMAND ----------

spark.readStream.table(dbutils.widgets.get("delta-table")).createOrReplaceGlobalTempView(dbutils.widgets.get("stream-temp-table"))

// COMMAND ----------

dbutils.notebook.exit("SUCCESS")

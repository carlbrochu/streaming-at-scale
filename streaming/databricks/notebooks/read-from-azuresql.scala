// Databricks notebook source
dbutils.widgets.text("secrets-scope", "MAIN", "Secrets scope")
dbutils.widgets.text("azuresql-servername", "servername")
dbutils.widgets.text("azuresql-finaltable", "[dbo].[rawdata]")
dbutils.widgets.text("delta-temp-table", "tmp_delta_table", "Staging delta table landing data from Azure SQL")
dbutils.widgets.text("stream-temp-table", "stream_data", "Spark global temp table to pass stream data")

// COMMAND ----------

import com.microsoft.azure.sqldb.spark.bulkcopy.BulkCopyMetadata
import com.microsoft.azure.sqldb.spark.config.Config
import com.microsoft.azure.sqldb.spark.connect._

val dbConfig = Config(Map(
  "url"               -> (dbutils.widgets.get("azuresql-servername") + ".database.windows.net"),
  "user"              -> "serveradmin",
  "password"          -> dbutils.secrets.get(scope = dbutils.widgets.get("secrets-scope"), key = "azuresql-pass"),
  "databaseName"      -> "streaming",
  "dbTable"           -> dbutils.widgets.get("azuresql-finaltable")
))   

// COMMAND ----------

spark
  .read
  .sqlDB(dbConfig)
  .write
  .format("delta")
  .mode("overwrite")
  .saveAsTable(dbutils.widgets.get("delta-temp-table"))

// COMMAND ----------

spark
  .readStream
  .table(dbutils.widgets.get("delta-temp-table"))
  .withColumn("processedAt", current_timestamp)
  .createOrReplaceGlobalTempView(dbutils.widgets.get("stream-temp-table"))

// COMMAND ----------

dbutils.notebook.exit("SUCCESS")

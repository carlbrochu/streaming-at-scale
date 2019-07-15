// Databricks notebook source
dbutils.widgets.text("secrets-scope", "MAIN", "Secrets scope")
dbutils.widgets.text("azuresql-servername", "servername")
dbutils.widgets.text("azuresql-stagingtable", "[dbo].[staging_table]")
dbutils.widgets.text("azuresql-finaltable", "[dbo].[rawdata]")
dbutils.widgets.text("azuresql-etlstoredproc", "[dbo].[stp_WriteDataBatch]")
dbutils.widgets.text("stream-temp-table", "stream_data", "Spark global temp table to pass stream data")

// COMMAND ----------

dbutils.notebook.run("read-from-azuresql", 0, List(
    "secrets-scope",
    "azuresql-servername",
    "azuresql-stagingtable",
    "azuresql-finaltable",
    "azuresql-etlstoredproc",
    "stream-temp-table"
  ).map(t=>t->dbutils.widgets.get(t)).toMap
)

// COMMAND ----------

dbutils.notebook.run("assert-stream-performance", 0, List(
    "secrets-scope",
    "stream-temp-table"
  ).map(t=>t->dbutils.widgets.get(t)).toMap
)

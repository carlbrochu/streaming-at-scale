// Databricks notebook source
dbutils.widgets.text("eventhub-secret-name", "eventhub-cs-in-read", "Event Hubs connection string key name in secret scope")
dbutils.widgets.text("eventhub-consumergroup", "azuresql")
dbutils.widgets.text("eventhub-maxEventsPerTrigger", "1000", "Event Hubs max events per trigger")
dbutils.widgets.text("azuresql-servername", "servername")
dbutils.widgets.text("azuresql-stagingtable", "[dbo].[staging_table]")
dbutils.widgets.text("azuresql-finaltable", "[dbo].[rawdata]")
dbutils.widgets.text("azuresql-etlstoredproc", "[dbo].[stp_WriteDataBatch]")
dbutils.widgets.text("stream-temp-table", "stream_data", "Spark global temp table to pass stream data")

// COMMAND ----------

dbutils.notebook.run("read-from-eventhubs", 0, List(
    "eventhub-consumergroup",
    "eventhub-maxEventsPerTrigger",
    "stream-temp-table"
  ).map(t=>t->dbutils.widgets.get(t)).toMap
)

// COMMAND ----------

dbutils.notebook.run("write-to-azuresql", 0, List(
    "azuresql-stagingtable",
    "azuresql-finaltable",
    "azuresql-etlstoredproc",
    "stream-temp-table"
  ).map(t=>t->dbutils.widgets.get(t)).toMap
)

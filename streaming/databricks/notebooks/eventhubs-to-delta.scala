// Databricks notebook source
dbutils.widgets.text("eventhub-consumergroup", "delta", "Event Hubs consumer group")
dbutils.widgets.text("eventhub-maxEventsPerTrigger", "1000", "Event Hubs max events per trigger")
dbutils.widgets.text("storage-account", "ADLSGEN2ACCOUNTNAME", "ADLS Gen2 storage account name")
dbutils.widgets.text("stream-temp-table", "stream_data", "Spark global temp table to pass stream data")

// COMMAND ----------

dbutils.notebook.run("read-from-eventhubs", 60, List(
    "eventhub-consumergroup",
    "eventhub-maxEventsPerTrigger",
    "stream-temp-table"
  ).map(t=>t->dbutils.widgets.get(t)).toMap
)

// COMMAND ----------

dbutils.notebook.run("write-to-delta", 0, List(
    "storage-account",
    "stream-temp-table"
  ).map(t=>t->dbutils.widgets.get(t)).toMap
)

// COMMAND ----------

// MAGIC %sql select * from stream_scale_events limit 10

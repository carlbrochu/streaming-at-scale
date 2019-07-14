// Databricks notebook source
dbutils.widgets.text("eventhub-consumergroup", "cosmos", "Event Hubs consumer group")
dbutils.widgets.text("eventhub-maxEventsPerTrigger", "1000", "Event Hubs max events per trigger")
dbutils.widgets.text("cosmosdb-endpoint", "https://MYACCOUNT.documents.azure.com", "Cosmos DB endpoint")
dbutils.widgets.text("cosmosdb-database", "streaming", "Cosmos DB database")
dbutils.widgets.text("cosmosdb-collection", "rawdata", "Cosmos DB collection")
dbutils.widgets.text("stream-temp-table", "stream_data", "Spark global temp table to pass stream data")

// COMMAND ----------

dbutils.notebook.run("read-from-eventhubs", 60, List(
    "eventhub-consumergroup",
    "eventhub-maxEventsPerTrigger",
    "stream-temp-table"
  ).map(t=>t->dbutils.widgets.get(t)).toMap
)

// COMMAND ----------

dbutils.notebook.run("write-to-cosmosdb", 0, List(
    "cosmosdb-endpoint",
    "cosmosdb-database",
    "cosmosdb-collection",
    "stream-temp-table"
  ).map(t=>t->dbutils.widgets.get(t)).toMap
)

// COMMAND ----------

// MAGIC %sql select * from stream_scale_events limit 10

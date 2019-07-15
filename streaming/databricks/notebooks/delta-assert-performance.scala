// Databricks notebook source
dbutils.widgets.text("delta-table", "streaming_events", "Delta table containing events")
dbutils.widgets.text("stream-temp-table", "stream_data", "Spark global temp table to pass stream data")

// COMMAND ----------

dbutils.notebook.run("read-from-delta", 0, List(
    "delta-table",
    "stream-temp-table"
  ).map(t=>t->dbutils.widgets.get(t)).toMap
)

// COMMAND ----------

dbutils.notebook.run("assert-stream-performance", 0, List(
    "stream-temp-table"
  ).map(t=>t->dbutils.widgets.get(t)).toMap
)

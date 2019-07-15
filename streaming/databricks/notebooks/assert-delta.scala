// Databricks notebook source
dbutils.widgets.text("secrets-scope", "MAIN", "Secrets scope")
dbutils.widgets.text("delta-table", "streaming_events", "Delta table containing events")
dbutils.widgets.text("stream-temp-table", "stream_data", "Spark global temp table to pass stream data")

// COMMAND ----------

dbutils.notebook.run("read-from-delta", 0, List(
    "secrets-scope",
    "delta-table",
    "stream-temp-table"
  ).map(t=>t->dbutils.widgets.get(t)).toMap
)

// COMMAND ----------

dbutils.notebook.run("assert-stream-performance", 0, List(
    "secrets-scope",
    "stream-temp-table"
  ).map(t=>t->dbutils.widgets.get(t)).toMap
)

// Databricks notebook source
dbutils.widgets.text("secrets-scope", "MAIN", "Secrets scope")
dbutils.widgets.text("stream-temp-table", "stream_data", "Spark global temp table to pass stream data")
dbutils.widgets.text("process-max-minutes", "5", "Max minutes timespan to process")
dbutils.widgets.text("assert-events-per-second", "900", "Assert min events per second (computed over 1 min windows)")
dbutils.widgets.text("assert-latency-milliseconds", "1000", "Assert max latency in milliseconds (averaged over 1 min windows)")

// COMMAND ----------

if (dbutils.secrets.list(dbutils.widgets.get("secrets-scope")).exists { s => s.key == "storage-account-key"}) {
  spark.conf.set("fs.azure.account.key", dbutils.secrets.get(scope = dbutils.widgets.get("secrets-scope"), key = "storage-account-key"))
}

// COMMAND ----------

val global_temp_db = spark.conf.get("spark.sql.globalTempDatabase")
val streamData = table(global_temp_db + "." + dbutils.widgets.get("stream-temp-table"))

// COMMAND ----------

import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming._
import java.util.UUID.randomUUID

val tempTable = "tempresult_" + randomUUID().toString.replace("-","_")

val streamStatistics = streamData
    .withColumn("storedAtMinute", (floor(unix_timestamp('enqueuedAt) / 60)  * 60).cast("timestamp"))
    .withColumn("latency", 'enqueuedAt.cast("double") - 'createdAt.cast("double"))
    .groupBy('storedAtMinute)
    .agg(
      (count('eventId)/60).as("events_per_second"),
      avg('latency)as("avg_latency_s") //, min('latency), max('latency)
    )
    .orderBy('storedAtMinute)

def asOptionalDouble (s:String) = if (s == null || s == "") None else Some(s.toDouble)
def getOptionalDouble (r:Row, i:Int) = if (r isNullAt i) None else Some(r getDouble i)

val processMaxMinutes = asOptionalDouble(dbutils.widgets.get("process-max-minutes"))
val assertEventsPerSecond = asOptionalDouble(dbutils.widgets.get("assert-events-per-second"))
val assertLatencyMilliseconds = asOptionalDouble(dbutils.widgets.get("assert-latency-milliseconds"))

var query:StreamingQuery = null
var stop = false
var startTime = 0L
var assertionsPassed = false
var numberOfMinutes = 0L

query = streamStatistics.writeStream
.outputMode("complete") 
.foreachBatch { (batchDF: DataFrame, batchId: Long) =>
  if (startTime == 0) {
    startTime = System.nanoTime
  }
  if (processMaxMinutes.isDefined && (System.nanoTime - startTime) > processMaxMinutes.get.toLong * 60 * 1e9) {
    stop = true
  }
  val stats = batchDF.agg(count('storedAtMinute), max('events_per_second), min('avg_latency_s)).head
  numberOfMinutes = stats.getLong(0)
  val maxEventsPerSecond = getOptionalDouble(stats, 1)
  val minAvgLatency = getOptionalDouble(stats, 2)
  var assertionsPassedInBatch = true
  if (maxEventsPerSecond.getOrElse(0d) < assertEventsPerSecond.getOrElse(Double.PositiveInfinity)) {
    assertionsPassedInBatch = false
  }
  if ((minAvgLatency.getOrElse(Double.PositiveInfinity) * 1000) > assertLatencyMilliseconds.getOrElse(0d)) {
    assertionsPassedInBatch = false
  }
  assertionsPassed = assertionsPassedInBatch
  if (assertionsPassed || stop) {
    batchDF.write.mode("overwrite").saveAsTable(tempTable)
    query.stop
  }
}
.start()

// COMMAND ----------

print("Waiting while stream collects data")
var printedNumberOfMinutes = -1L
while (query.isActive) {
  print(".")
  if (numberOfMinutes != printedNumberOfMinutes) {
    println()
    print(s"Number of minutes of data collected: $numberOfMinutes ")
    printedNumberOfMinutes = numberOfMinutes
  }
  Thread.sleep(1000)
}
println()
assert(query.exception.isEmpty, "Exception in stream query")

// COMMAND ----------

spark.catalog.refreshTable(tempTable)
val tempTableData = table(tempTable).cache
display(tempTableData)

// COMMAND ----------

display(tempTableData)

// COMMAND ----------

sql(s"drop table if exists `$tempTable`")

// COMMAND ----------

assert (!stop || assertionsPassed, "Test assertion(s) failed")

// COMMAND ----------

dbutils.notebook.exit("SUCCESS")

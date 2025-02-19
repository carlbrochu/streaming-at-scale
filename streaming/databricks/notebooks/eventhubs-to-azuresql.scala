// Databricks notebook source
dbutils.widgets.text("eventhub-consumergroup", "azuresql")
dbutils.widgets.text("azuresql-servername", "servername")
dbutils.widgets.text("azuresql-stagingtable", "[dbo].[staging_table]")
dbutils.widgets.text("azuresql-finaltable", "[dbo].[rawdata]")
dbutils.widgets.text("azuresql-etlstoredproc", "[dbo].[stp_WriteDataBatch]")
dbutils.widgets.text("eventhub-maxEventsPerTrigger", "1000", "Event Hubs max events per trigger")

// COMMAND ----------

import org.apache.spark.eventhubs._

val ehConf = EventHubsConf(dbutils.secrets.get(scope = "MAIN", key = "event-hubs-read-connection-string"))
  .setConsumerGroup(dbutils.widgets.get("eventhub-consumergroup"))
  .setStartingPosition(EventPosition.fromEndOfStream)
  .setMaxEventsPerTrigger(dbutils.widgets.get("eventhub-maxEventsPerTrigger").toLong)

val reader = spark.readStream
  .format("eventhubs")
  .options(ehConf.toMap)
  .load()

// COMMAND ----------

import org.apache.spark.sql.types._
import org.apache.spark.sql.functions._

val schema = StructType(
  StructField("eventId", StringType) ::
  StructField("complexData", StructType((1 to 22).map(i => StructField(s"moreData$i", DoubleType)))) ::
  StructField("value", StringType) ::
  StructField("type", StringType) ::
  StructField("deviceId", StringType) ::
  StructField("createdAt", StringType) :: Nil)

// COMMAND ----------

val dataToWrite = reader
  .select(from_json(decode($"body", "UTF-8"), schema).as("eventData"), $"*")
  .select($"eventData.*", $"offset", $"sequenceNumber", $"publisher", $"partitionKey".cast(IntegerType), $"enqueuedTime".as("enqueuedAt")) 
  .withColumn("createdAt", $"createdAt".cast(TimestampType))
  .withColumn("processedAt", current_timestamp())
  .withColumn("StoredAt", current_timestamp()) 
  .select($"eventId".as("EventId"), $"Type", $"DeviceId", $"CreatedAt", $"Value", $"ComplexData", $"EnqueuedAt", $"ProcessedAt", $"StoredAt", $"PartitionKey".as("PartitionId"))

// COMMAND ----------

// Helper method to retry an operation up to n times with exponential backoff
@annotation.tailrec
final def retry[T](n: Int, backoff: Int)(fn: => T): T = {
  Thread.sleep(((scala.math.pow(2, backoff) - 1) * 1000).toLong)
  util.Try { fn } match {
    case util.Success(x) => x
    case _ if n > 1 => retry(n - 1, backoff + 1)(fn)
    case util.Failure(e) => throw e
  }
}

// COMMAND ----------

val serverName = dbutils.widgets.get("azuresql-servername")
val stagingTable =dbutils.widgets.get("azuresql-stagingtable")
val destinationTable = dbutils.widgets.get("azuresql-finaltable")
val etlStoredProc = dbutils.widgets.get("azuresql-etlstoredproc")

val jdbcUrl = s"jdbc:sqlserver://$serverName.database.windows.net;database=streaming"
val connectionProperties = new java.util.Properties()
connectionProperties.put("user", "serveradmin")
connectionProperties.put("password", dbutils.secrets.get(scope = "MAIN", key = "azuresql-pass"))
connectionProperties.setProperty("Driver", "com.microsoft.sqlserver.jdbc.SQLServerDriver")

val numPartitions = retry (6, 0) {
  val conn = java.sql.DriverManager.getConnection(jdbcUrl, connectionProperties)
  // This stored procedure merges data in batch from staging_table to the final table
  // ensuring deduplication.
  val getPartitionCount = conn.prepareStatement("select count(*) from sys.dm_db_partition_stats where object_id = object_id(?) and index_id < 1")
  getPartitionCount.setString(1, destinationTable)
  val getPartitionCountRs = getPartitionCount.executeQuery()
  getPartitionCountRs.next
  val numPartitions = getPartitionCountRs.getInt(1)
  getPartitionCountRs.close
  getPartitionCount.close
  conn.close
  if (numPartitions>1) numPartitions else 1
}

// COMMAND ----------

import com.microsoft.azure.sqldb.spark.bulkcopy.BulkCopyMetadata
import com.microsoft.azure.sqldb.spark.config.Config

val bulkCopyConfig = Config(Map(
  "url"               -> s"$serverName.database.windows.net",
  "user"              -> "serveradmin",
  "password"          -> dbutils.secrets.get(scope = "MAIN", key = "azuresql-pass"),
  "databaseName"      -> "streaming",
  "dbTable"           -> stagingTable,
  "bulkCopyBatchSize" -> "2500",
  "bulkCopyTableLock" -> "false",
  "bulkCopyTimeout"   -> "600"
))   

var bulkCopyMetadata = new BulkCopyMetadata
bulkCopyMetadata.addColumnMetadata(1, "EventId", java.sql.Types.NVARCHAR, 128, 0)
bulkCopyMetadata.addColumnMetadata(2, "Type", java.sql.Types.NVARCHAR, 10, 0)
bulkCopyMetadata.addColumnMetadata(3, "DeviceId", java.sql.Types.NVARCHAR, 100, 0)
bulkCopyMetadata.addColumnMetadata(4, "CreatedAt", java.sql.Types.NVARCHAR, 128, 0)
bulkCopyMetadata.addColumnMetadata(5, "Value", java.sql.Types.NVARCHAR, 128, 0)
bulkCopyMetadata.addColumnMetadata(6, "ComplexData", java.sql.Types.NVARCHAR, -1, 0)
bulkCopyMetadata.addColumnMetadata(7, "EnqueuedAt", java.sql.Types.NVARCHAR, 128, 0)
bulkCopyMetadata.addColumnMetadata(8, "ProcessedAt", java.sql.Types.NVARCHAR, 128, 0)
bulkCopyMetadata.addColumnMetadata(9, "PartitionId", java.sql.Types.INTEGER, 0, 0)


// COMMAND ----------

import com.microsoft.azure.sqldb.spark.connect._
import java.util.UUID.randomUUID
import org.apache.spark.sql.DataFrame

val generateUUID = udf(() => randomUUID().toString)

var writeDataBatch : java.sql.PreparedStatement = null

val WriteToSQLQuery  = dataToWrite
  .writeStream
  .option("checkpointLocation", "dbfs:/checkpoints/streaming-to-azuresql")
  .foreachBatch((batchDF: DataFrame, batchId: Long) => retry(6, 0) {
    
  // Load data into staging table.
  batchDF
    .withColumn("PartitionId", pmod(hash('DeviceId), lit(numPartitions)))
     .select('EventId, 'Type, 'DeviceId, 'CreatedAt, 'Value, 'ComplexData, 'EnqueuedAt, 'ProcessedAt, 'PartitionId)
    .bulkCopyToSqlDB(bulkCopyConfig, bulkCopyMetadata)

  if (writeDataBatch == null) {
    val conn  = java.sql.DriverManager.getConnection(jdbcUrl, connectionProperties)
    // This stored procedure merges data in batch from staging_table to the final table
    // ensuring deduplication.
    writeDataBatch = conn.prepareCall(s"{call $etlStoredProc}")
  }
  try {
    // Here we run the ETL process after each microbatch.
    // We could also run it less frequently, or run it asynchronously through an external mechanism
    // (but in that case we'd have to run the stored procedure in a serializable transaction).
    writeDataBatch.execute
  }
  catch {
    case e: Exception => {
      // Tolerate transient database connectivity errors by reconnecting in case of failure.
      writeDataBatch = null
      throw e
    }
  }
})

var streamingQuery = WriteToSQLQuery.start()

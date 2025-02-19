---
topic: sample
languages:
  - azurecli
  - json
  - sql
  - scala
products:
  - azure
  - azure-container-instances
  - azure-databricks
  - azure-event-hubs
  - azure-sql-database
statusNotificationTargets:
  - damauri@microsoft.com
---

# Streaming at Scale with Azure Event Hubs, Databricks and Azure SQL

This sample uses Cosmos DB as database to store JSON data

The provided scripts will an end-to-end solution complete with load test client.

## Running the Scripts

Please note that the scripts have been tested on [Ubuntu 18 LTS](http://releases.ubuntu.com/18.04/), so make sure to use that environment to run the scripts. You can run it using Docker, WSL or a VM:

- [Ubuntu Docker Image](https://hub.docker.com/_/ubuntu/)
- [WSL Ubuntu 18.04 LTS](https://www.microsoft.com/en-us/p/ubuntu-1804-lts/9n9tngvndl3q?activetab=pivot:overviewtab)
- [Ubuntu 18.04 LTS Azure VM](https://azuremarketplace.microsoft.com/en-us/marketplace/apps/Canonical.UbuntuServer1804LTS)

The following tools/languages are also needed:

- [Azure CLI](https://docs.microsoft.com/en-us/cli/azure/install-azure-cli-apt?view=azure-cli-latest)
  - Install: `sudo apt install azure-cli`
- [jq](https://stedolan.github.io/jq/download/)
  - Install: `sudo apt install jq`
- [python]
  - Install: `sudo apt install python python-pip`
- [databricks-cli](https://github.com/databricks/databricks-cli)
  - Install: `pip install --upgrade databricks-cli`

## Setup Solution

Make sure you are logged into your Azure account:

    az login

and also make sure you have the subscription you want to use selected

    az account list

if you want to select a specific subscription use the following command

    az account set --subscription <subscription_name>

once you have selected the subscription you want to use just execute the following command

    ./create-solution.sh -d <solution_name>

then `solution_name` value will be used to create a resource group that will contain all resources created by the script. It will also be used as a prefix for all resource create so, in order to help to avoid name duplicates that will break the script, you may want to generate a name using a unique prefix. **Please also use only lowercase letters and numbers only**, since the `solution_name` is also used to create a storage account, which has several constraints on characters usage:

[Storage Naming Conventions and Limits](https://docs.microsoft.com/en-us/azure/architecture/best-practices/naming-conventions#storage)

to have an overview of all the supported arguments just run

    ./create-solution.sh

**Note**
To make sure that name collisions will be unlikely, you should use a random string to give name to your solution. The following script will generated a 7 random lowercase letter name for you:

    ./common/generate-solution-name.sh

## Created resources

The script will create the following resources:

- **Azure Container Instances** to host [Locust](https://locust.io/) Load Test Clients: by default two Locust client will be created, generating a load of 1000 events/second
- **Event Hubs** Namespace, Hub and Consumer Group: to ingest data incoming from test clients
- **Azure Databricks**: to process data incoming from Event Hubs as a stream. Workspace, Job and related cluster will be created
- **Azure SQL** Server and Database: to store and serve processed data

## Streamed Data

Streamed data simulates an IoT device sending the following JSON data:

```json
{
    "eventId": "b81d241f-5187-40b0-ab2a-940faf9757c0",
    "complexData": {
        "moreData0": 57.739726013343247,
        "moreData1": 52.230732688620829,
        "moreData2": 57.497518587807189,
        "moreData3": 81.32211656749469,
        "moreData4": 54.412361539409427,
        "moreData5": 75.36416309399911,
        "moreData6": 71.53407865773488,
        "moreData7": 45.34076957651598,
        "moreData8": 51.3068118685458,
        "moreData9": 44.44672606436184,
        [...]
    },
    "value": 49.02278128887753,
    "deviceId": "contoso://device-id-154",
    "type": "CO2",
    "createdAt": "2019-05-16T17:16:40.000003Z"
}
```

## Duplicate handling

In case the Databricks job fails and recovers, it could process a second time an event from Event Hubs that has already been stored in Azure SQL Database. The solution implements an ETL process to make this operation idempotent, so that events are not duplicated in Azure SQL Database (based on the eventId attribute). The process has been engineered for performance at high throughput.

Data is first [bulk loaded](https://docs.microsoft.com/en-us/sql/t-sql/statements/bulk-insert-transact-sql)
into a staging table whose partition scheme is aligned to that of the target table. Then, a stored procedure is run within Azure SQL Database in order to first deduplicate events from the staging table, then insert only new events into the target table, and finally the staging table is cleared (see [Databricks notebook](../streaming/databricks/notebooks/eventhubs-to-azuresql.scala)).

Due to lookups, performance can be expected to decrease as data accumulates in the target table, so you should perform careful measurements for your scenario. The ETL process introduces a performance bottleneck. In the chart below, processing is not keeping up with the event generation rate of 10k events per second, although the database is running at a high P6 tier (the spike around 20:54 is due to an intermittent database disconnection while running the ETL stored procedure, and demonstrates that the job seamlessly recovers from such a transient failure).

![Console Performance Report](etl-performance.png)

The stored procedure code is as follows:



```sql
CREATE PROCEDURE [dbo].[stp_WriteDataBatch] 
as
  -- Move events from staging_table to rawdata table.
  -- WARNING: This procedure is non transaction to optimize performance, and
  --          assumes no concurrent writes into the staging_table during its execution.
  declare @buid uniqueidentifier = newId();

  -- ETL logic: insert events if they do not already exist in destination table
WITH staging_data_with_partition AS
(
	SELECT * 
	FROM dbo.staging_table
)
MERGE dbo.rawdata AS t
    USING (

      -- Deduplicate events from staging table
      SELECT  *
      FROM (SELECT *,
	    ROW_NUMBER() OVER (PARTITION BY PartitionId, EventId ORDER BY EnqueuedAt) AS RowNumber
        FROM staging_data_with_partition
        ) AS StagingDedup
      WHERE StagingDedup.RowNumber = 1

    ) AS s
        ON s.PartitionId = t.PartitionId AND s.EventId = t.EventId

    WHEN NOT MATCHED THEN
        INSERT (PartitionId, EventId, Type, DeviceId, CreatedAt, Value, ComplexData, 
	                EnqueuedAt, ProcessedAt, BatchId, StoredAt) 
        VALUES (s.PartitionId, s.EventId, s.Type, s.DeviceId, s.CreatedAt, s.Value, s.ComplexData,
	                s.EnqueuedAt, s.ProcessedAt, @buid, sysutcdatetime())
        ;

TRUNCATE TABLE dbo.staging_table;
```

## Solution customization

If you want to change some setting of the solution, like number of load test clients, Cosmos DB RU and so on, you can do it right in the `create-solution.sh` script, by changing any of these values:

```bash
    export EVENTHUB_PARTITIONS=4
    export EVENTHUB_CAPACITY=2
    export SQL_SKU=P2
    export TEST_CLIENTS=3 
    export DATABRICKS_NODETYPE=Standard_DS3_v2
    export DATABRICKS_WORKERS=4
    export DATABRICKS_MAXEVENTSPERTRIGGER=10000
```

The above settings has been chosen to sustain a 1,000 msg/s stream. The script also contains settings for 5,000 msg/s and 10,000 msg/s.

## Monitor performances

Performance will be monitored and displayed on the console for 30 minutes also. More specifically Inputs and Outputs performance of Event Hub will be monitored. If everything is working corretly, the number of reported `IncomingMessages` and `OutgoingMessages` should be roughly the same. (Give couple of minutes for ramp-up)

![Console Performance Report](../_doc/_images/console-performance-monitor.png)

## Azure SQL

The solution allows you to test both row-store and column-store options. The deployed database has four tables

- `rawdata`
- `rawdata_cs`
- `rawdata_cs_mo`
- `rawdata_mo`

The suffix indicates which kind of storage is used for the table:

- No suffix: classic row-store table
- `cs`: column-store via clustered columnstore index
- `mo`: memory-optimized table
- `cs_mo`: memory-optimized clustered columnstore

Use the `-k` option and set it to `rowstore` or `columnstore`. At present time the sample doesn't support using Memory-Optimized tables yet.

Be aware that database log backup happens every 10 minutes circa, as described here: [Automated backups](https://docs.microsoft.com/en-us/azure/sql-database/sql-database-automated-backups#how-often-do-backups-happen). This means that additional IO overhead needs to be taken into account, which is proportional to the amount of ingested rows. That's why to move from 5000 msgs/sec to 10000 msgs/sec a bump from P4 to P6 is needed. The Premium level provides much more I/Os which are needed to allow backup to happen without impacting performances.

If you want to connect to Azure SQL to query data and/or check resources usages, here's the login and password:

```
User ID = serveradmin
Password = Strong_Passw0rd!
```

## Azure Databricks

Table Valued Parameters could not be used as the [`SQLServerDataTable` class](https://docs.microsoft.com/en-us/sql/connect/jdbc/using-table-valued-parameters?view=sql-server-2017#passing-a-table-valued-parameter-as-a-sqlserverdatatable-object) is not serializable and thus not usable with the `forEach` sink. The [`forEachBatch` sink](https://spark.apache.org/docs/latest/structured-streaming-programming-guide.html#foreachbatch) has been used instead, and therefore BULK INSERT has been choosed as the best solution to quickly load data into Azure SQL.

Bulk insert logic has been already implemented in the Azure SQL Spark Connector library, which is used in the `forEachBatch` function to perform the Bulk Load:

[Spark connector for Azure SQL Databases and SQL Server](https://github.com/Azure/azure-sqldb-spark)

`forEachBatch` works on all the DataFrame partitions, so `BatchId % 16` is used to spread data in all the 16 partitions available in Azure SQL.

One interesting aspect to notice is that the number of EventHubs partition is different and higher that the number of allocated Throughput Units (TU). For example, with 5000 msgs/sec 6 TU are used, but 10 partitions are needed. The 6 TU are more than enough to sustain 5000 msgs/sec (as each 1 TU supports 1 Mb and 1000 msgs/sec), but in order to process data fast enough, Databricks needs to have 10 workers to be able to deal with the incoming messages. In order to make sure each worker reads from a partition without interfering with another worker, a worker should be created for each Event Hub partition.

## Query Data

Usage of [sp_whoisactive](http://whoisactive.com/) is recommended to see what's going on in Azure SQL. All tables have the following schema:

![RawData Table Schema](../_doc/_images/databricks-azuresql-table.png)

## Clean up

To remove all the created resource, you can just delete the related resource group

```bash
az group delete -n <resource-group-name>
```

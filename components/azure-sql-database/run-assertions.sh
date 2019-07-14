#!/bin/bash

# Strict mode, fail on any error
set -euo pipefail

# Compute metrics over 5-minute ranges to avoid extreme spikes due to batching
metricsQuery=$(cat<<SQLQUERY
SET NOCOUNT ON;
WITH thr AS (
  SELECT count(*)/300 AS events_per_second
    , avg(datediff(millisecond, EnqueuedAt, StoredAt)) AS latency_ms
  FROM $SQL_EVENTS_TABLE
  GROUP BY datediff(second, cast(19700101 as nvarchar), StoredAt) / 300 -- 5 minute interval
)
SELECT max(events_per_second) AS events_per_second, min(latency_ms) AS latency_ms
FROM thr
FOR JSON AUTO

SQLQUERY
)

uniqueContainerName="sqlclient-$(date +%s)"

# Run sqlcmd in an Azure container instance, to avoid firewall issues from client machine
echo 'creating SQL client container'
az container create -o none -g $RESOURCE_GROUP -n $uniqueContainerName \
    --image mcr.microsoft.com/mssql-tools:v1 \
    --restart-policy OnFailure \
    --environment-variables SQL_SERVER="$SQL_SERVER_NAME.database.windows.net" SQL_USERNAME=serveradmin SQL_DBNAME="$SQL_DATABASE_NAME" \
    --secure-environment-variables SQL_PASSWORD="$SQL_ADMIN_PASS" \
    --command-line "/bin/bash -c \"/opt/mssql-tools/bin/sqlcmd -I -h-1 -w 65535 -U \$SQL_USERNAME -P \$SQL_PASSWORD -S \$SQL_SERVER -d \$SQL_DBNAME -Q '$metricsQuery'\""

echo 'waiting for SQL client command output'
for i in {1..600}; do
  state=$(az container show -g $RESOURCE_GROUP -n $uniqueContainerName --query 'containers[].instanceView.currentState.state' -o tsv)
  if [ "$state" == "Terminated" ]; then
    break
  fi
  sleep 1
done

containerOutput=$(az container logs -g $RESOURCE_GROUP -n $uniqueContainerName)
if ! jq . > /dev/null <<< "$containerOutput"; then
  echo "Unexpected output from SQL client:"
  echo $containerOutput
  exit 1
fi

echo 'deleting SQL client container'
az container delete -g $RESOURCE_GROUP -n $uniqueContainerName -y -o none

FAIL=""
function assert {
if ! "$@"; then
  echo "Assertion failed";
  FAIL="1"
fi
}

echo ""
echo 'Verification steps:'

maxEventsPerSecond=$(jq '.[].events_per_second' <<< "$containerOutput")
maxEventsPerSecondExpected=$(($TESTTYPE * 1000 * 9/10))
echo "Max throughput: $maxEventsPerSecond events/second, expecting >=$maxEventsPerSecondExpected"
assert test "$maxEventsPerSecond" -ge "$maxEventsPerSecondExpected"

avgLatency=$(jq '.[].latency_ms' <<< "$containerOutput")
avgLatencyExpected=1000
echo "Min latency: $avgLatency milliseconds, expecting <=$avgLatencyExpected"
assert test "$avgLatency" -le "$avgLatencyExpected"

if [ -n "$FAIL" ]; then
  echo "Verification failed."
  exit 1
fi
echo "Verification succeeded."

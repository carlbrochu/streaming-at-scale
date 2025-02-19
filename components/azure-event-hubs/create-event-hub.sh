#!/bin/bash

set -euo pipefail

EVENTHUB_CAPTURE=${EVENTHUB_CAPTURE:-False}
EVENTHUB_NAMESPACES=${EVENTHUB_NAMESPACES:-$EVENTHUB_NAMESPACE}

for eventHubsNamespace in $EVENTHUB_NAMESPACES; do

echo 'creating eventhubs namespace'
echo ". name: $eventHubsNamespace"
echo ". capacity: $EVENTHUB_CAPACITY"
echo ". capture: $EVENTHUB_CAPTURE"
echo ". auto-inflate: false"

az eventhubs namespace create -n $eventHubsNamespace -g $RESOURCE_GROUP \
    --sku Standard --location $LOCATION --capacity $EVENTHUB_CAPACITY \
    --enable-auto-inflate false \
    -o tsv >> log.txt

echo 'creating eventhub instance'
echo ". name: $EVENTHUB_NAME"
echo ". partitions: $EVENTHUB_PARTITIONS"

az eventhubs eventhub create -n $EVENTHUB_NAME -g $RESOURCE_GROUP \
    --message-retention 1 --partition-count $EVENTHUB_PARTITIONS --namespace-name $eventHubsNamespace \
    --enable-capture "$EVENTHUB_CAPTURE" --capture-interval 300 --capture-size-limit 314572800 \
    --archive-name-format '{Namespace}/{EventHub}/{Year}_{Month}_{Day}_{Hour}_{Minute}_{Second}_{PartitionId}' \
    --blob-container eventhubs \
    --destination-name 'EventHubArchive.AzureBlockBlob' \
    --storage-account $AZURE_STORAGE_ACCOUNT \
    -o tsv >> log.txt

if [ -n "${EVENTHUB_CG:-}" ]; then
echo 'creating consumer group'
echo ". name: $EVENTHUB_CG"

az eventhubs eventhub consumer-group create -n $EVENTHUB_CG -g $RESOURCE_GROUP \
    --eventhub-name $EVENTHUB_NAME --namespace-name $eventHubsNamespace \
    -o tsv >> log.txt
fi

az eventhubs eventhub authorization-rule create -g $RESOURCE_GROUP --eventhub-name $EVENTHUB_NAME \
    --namespace-name $eventHubsNamespace \
    --name Listen --rights Listen \
    -o tsv >> log.txt

az eventhubs eventhub authorization-rule create -g $RESOURCE_GROUP --eventhub-name $EVENTHUB_NAME \
    --namespace-name $eventHubsNamespace \
    --name Send --rights Send \
    -o tsv >> log.txt

done

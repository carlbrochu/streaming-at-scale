from locust import HttpLocust, TaskSet, task
import os
import random
import requests
import datetime, time
import uuid
import sys    
import urllib
from urllib.parse import quote, quote_plus
import hmac
import hashlib
import base64
import json

from requests.packages.urllib3.exceptions import InsecureRequestWarning
requests.packages.urllib3.disable_warnings(InsecureRequestWarning)

def get_auth_token(eh_namespace, eh_name, eh_key):
    uri = quote_plus("https://{0}.servicebus.windows.net/{1}".format(eh_namespace, eh_name))
    eh_key = eh_key.encode('utf-8')
    expiry = str(int(time.time() + 60 * 60 * 24 * 31))
    string_to_sign = (uri + '\n' + expiry).encode('utf-8')
    signed_hmac_sha256 = hmac.HMAC(eh_key, string_to_sign, hashlib.sha256)
    signature = quote(base64.b64encode(signed_hmac_sha256.digest()))
    return 'SharedAccessSignature sr={0}&sig={1}&se={2}&skn={3}'.format(uri, signature, expiry, "RootManageSharedAccessKey")

EVENT_HUB = {
    'namespace': os.environ['EVENTHUB_NAMESPACE'],
    'name': os.environ['EVENTHUB_NAME'],
    'key': os.environ['EVENTHUB_KEY'],
    'token': get_auth_token(os.environ['EVENTHUB_NAMESPACE'], os.environ['EVENTHUB_NAME'], os.environ['EVENTHUB_KEY'])
}

duplicateEveryNEvents = int(os.environ.get('DUPLICATE_EVERY_N_EVENTS') or 0)

class DeviceSimulator(TaskSet):
    headers = {
        'Content-Type': 'application/atom+xml;type=noretry;charset=utf-8 ',
        'Authorization': EVENT_HUB['token'],
        'Host': EVENT_HUB['namespace'] + '.servicebus.windows.net'
    }

    endpoint = "/" + EVENT_HUB['name'] + "/messages?timeout=60&api-version=2014-01"
    
    ccd = int(os.environ.get('COMPLEX_DATA_COUNT') or "23")

    def __sendData(self, payloadType):
        eventId = str(uuid.uuid4())
        createdAt = str(datetime.datetime.utcnow().replace(microsecond=3).isoformat()) + "Z"

        deviceIndex = random.randint(0, 999)

        jsonBody={
            'eventId': eventId,
            'type': payloadType,
            'deviceId': 'contoso://device-id-{0}'.format(deviceIndex),
            'createdAt': createdAt,
            'value': random.uniform(10,100),
            'complexData': {                           
            }
        }

        for cd in range(self.ccd):
            jsonBody['complexData']["moreData{}".format(cd)] = random.uniform(10,100)

        headers = dict(self.headers)
        brokerProperties = { 'PartitionKey': str(deviceIndex) }
        headers["BrokerProperties"] = json.dumps(brokerProperties)


        n = 2 if duplicateEveryNEvents>0 and random.randrange(duplicateEveryNEvents) == 0 else 1
        for i in range(n):
            self.client.post(self.endpoint, json=jsonBody, verify=False, headers=headers)

    @task
    def sendTemperature(self):
        self.__sendData("TEMP")

    @task
    def sendCO2(self):
        self.__sendData("CO2")

class MyLocust(HttpLocust):
    task_set = DeviceSimulator
    min_wait = 250
    max_wait = 500

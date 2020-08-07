#!/bin/bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

#
# Update an entity by type and unique attribute
#

realScriptDir=$(cd "$(dirname "$0")"; pwd)

source ${realScriptDir}/env_atlas.sh
source ./env_atlas.sh

typeName=$1
attrName=$2
attrValue=$3
inputFileName=$4
outputFileName=$5


function checkUsage() {
  if [ "${typeName}" == "" -o "${attrName}" == "" -o "${attrValue}" == "" -o "${inputFileName}" == "" ]
  then
    echo "Usage: $0 typeName attrName attrValue inputFileName [outputFileName]"
    exit 1
  fi
}
checkUsage

url=${ATLAS_URL}/api/atlas/v2/entity/uniqueAttribute/type/${typeName}/attribute/${attrName}?value=${attrValue}

output=`${CURL_CMDLINE} -X PUT -u ${ATLAS_USER}:${ATLAS_PASS} -H "Accept: application/json" -H "Content-Type: application/json" ${url} -d @${inputFileName}`
ret=$?

if [ $ret == 0 ]
then
  if [ "${outputFileName}" == "" ]
  then
    echo ${output} | ${JSON_FORMATTER}
  else
    echo ${output} | ${JSON_FORMATTER} | tee ${outputFileName}
    echo "saved to ${outputFileName}"
  fi
else
  echo "failed with error code: ${ret}"
fi

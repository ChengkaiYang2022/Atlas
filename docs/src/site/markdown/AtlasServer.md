<!--
  ~ Licensed to the Apache Software Foundation (ASF) under one
  ~ or more contributor license agreements.  See the NOTICE file
  ~ distributed with this work for additional information
  ~ regarding copyright ownership.  The ASF licenses this file
  ~ to you under the Apache License, Version 2.0 (the
  ~ "License"); you may not use this file except in compliance
  ~ with the License.  You may obtain a copy of the License at
  ~
  ~     http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing, software
  ~ distributed under the License is distributed on an "AS IS" BASIS,
  ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  ~ See the License for the specific language governing permissions and
  ~ limitations under the License.
  -->
# Atlas Server Entity Type

#### Background

The _AtlasServer_ entity type is special entity type in following ways:

* Gets created during Export or Import operation. 
* It also has special property pages that display detailed audits for export and import operations.
* Entities are linked to it using the new option within entity's attribute _[SoftReference](SoftReference)_.

The new type is available within the _Search By Type_ dropdown in both _Basic_ and _Advanced_ search.

#### Creation

The entity of this type is created upon successful completion of every Export and Import operation. The entity is created with current cluster's name.

The entity is also created based on export and import requests' _replicatedTo_ and _replicatedFrom_ parameters.

#### Details within Property Page

The property page for _AtlasServer_ entity has one additional tab 'Export/Import Audits'. This has detailed audit record for each export and/or import operation performed on current Atlas instance.

The _additionalInfo_ attribute property is discussed in detail below.

<img src="images/markdown/atlas-server-properties.png" style="border:1px solid; margin-left:25px"/>


###### Export/Import Audits

The table has following columns:

* _Operation_: EXPORT or IMPORT that denotes the operation performed on instance.
* _Source Server_: For an export operation performed on this instance, the value in this column will always be the cluster name of the current Atlas instance. This is the value specified in _atlas-application.properties_ by the key _atlas.cluster.name_. If not value is specified 'default' is used.
* _Target Server_: If an export operation is performed with _replicatedTo_ property specified in the request, that value appears here.
* _Operation StartTime_: Time the operation was started.
* _Operation EndTIme_: Time the operation completed.
* _Tools_: Pop-up property page that contains details of the operation.

<img src="images/markdown/atlas-server-exp-imp-audits.png" style="border:1px solid; margin-left:25px"/>


###### Example

The following export request will end up creating _AtlasServer_ entity with _clMain_ as its name. The audit record of this operation will be displayed within the property page of this entity.
```json 
{
    "itemsToExport": [
        { "typeName": "hive_db", "uniqueAttributes": { "qualifiedName": "stocks@cl1" }}
    ],
    "options": {
        "replicatedTo": "clMain"
    }
}
```

#### Support for Cluster's Full Name

Often times it is necessary to disambiguate the name of the cluster by specifying the location or the data center within which the Atlas instance resides. 

The name of the cluster can be specified by separating the location name and cluster name by '$'. For example, a clsuter name specified as 'SFO$cl1' can be a cluster in San Fancisco (SFO) data center with the name 'cl1'.

The _AtlasServer_ will handle this and set its name as 'cl1' and _fullName_ as 'SFO@cl1'.


#### Additional Information

This property in _AtlasServer_ is a map with key and value both as String. This can be used to store any information pertaining to this instance. 

Please see [Incremental Export](IncrementalExport) for and example of how this property can be used.

#### REST APIs
Title           |Atlas Server API                       |
----------------|---------------------------------------|
Example         |   see below.                          |
URL             | api/atlas/admin/server/{serverName}   |
Method          | GET                                   |
URL Parameters  | name of the server                    |
Data Parameters | None                                  |
Success Response| _AtlasServer_                         |
Error Response  | Errors Returned as AtlasBaseException |

###### CURL

```
curl -X GET -u admin:admin -H "Content-Type: application/json" -H "Cache-Control: no-cache" http://localhost:21000/api/atlas/admin/server/cl2
```

Output: 
```json
{
    "guid": "f87e4fd1-bfb5-482d-9ab1-e735621b7d16",
    "name": "cl2",
    "qualifiedName": "cl2",
    "additionalInfo": {
        "nextModifiedTimestamp": "1533037289383",
        "replicationOperation": "EXPORT",
        "topLevelEntity": "stocks@cl1"
    }
}
```



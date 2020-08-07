# Entity Attribute Option: SoftReference

#### Background

Entity attributes are specified using attribute definitions. An attributes persistence strategy is determined by based on their type. 

Primitive types are persisted as properties within the vertex of their parent. 

Non-primitive attributes get a vertex of their own and and edge is created between the parent the child to establish ownership.

Attribute with _isSoftReference_ option set to _true_, is non-primitive attribute that gets treatment of a primitive attribute.

#### Specification

Below is an example of using the new attribute option.

```json 
  "attributeDefs": [
    {
      "name": "replicatedFrom",
      "typeName": "array<AtlasServer>",
      "cardinality": "SET",
      "isIndexable": false,
      "isOptional": true,
      "isUnique": false,
      "options": {
        "isSoftReference": "true"
      }
    },
```


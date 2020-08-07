#/usr/local/src/solr/solr-7.5.0/bin/solr create -c  vertex_index -d /usr/local/src/solr/solr-7.5.0/atlas-solr -shards 3 -replicationFactor 2
#/usr/local/src/solr/solr-7.5.0/bin/solr create -c  edge_index -d /usr/local/src/solr/solr-7.5.0/atlas-solr -shards 3 -replicationFactor 2
#/usr/local/src/solr/solr-7.5.0/bin/solr create -c  fulltext_index -d /usr/local/src/solr/solr-7.5.0/atlas-solr -shards 3 -replicationFactor 2



/opt/cloudera/parcels/CDH-6.2.1-1.cdh6.2.1.p0.1425774/lib/solr/bin/solr create -c vertex_index -d /opt/cloudera/parcels/CDH-6.2.1-1.cdh6.2.1.p0.1425774/etc/solr/conf.dist/ -shards 1 -replicationFactor 1
/opt/cloudera/parcels/CDH-6.2.1-1.cdh6.2.1.p0.1425774/lib/solr/bin/solr create -c edge_index -d /opt/cloudera/parcels/CDH-6.2.1-1.cdh6.2.1.p0.1425774/etc/solr/conf.dist/ -shards 1 -replicationFactor 1
/opt/cloudera/parcels/CDH-6.2.1-1.cdh6.2.1.p0.1425774/lib/solr/bin/solr create -c fulltext_index -d /opt/cloudera/parcels/CDH-6.2.1-1.cdh6.2.1.p0.1425774/etc/solr/conf.dist/ -shards 1 -replicationFactor 1

#
#kafka-topics --zookeeper 10.10.6.102:2181,10.10.6.103:2181,10.10.6.104:2181 --create --replication-factor 3 --partitions 3 --topic _HOATLASOK
#kafka-topics --zookeeper 10.10.6.102:2181,10.10.6.103:2181,10.10.6.104:2181 --create --replication-factor 3 --partitions 3 --topic ATLAS_ENTITIES
#kafka-topics --zookeeper 10.10.6.102:2181,10.10.6.103:2181,10.10.6.104:2181 --create --replication-factor 3 --partitions 3 --topic ATLAS_HOOK



kafka-topics --zookeeper master:2181 --create --replication-factor 1 --partitions 3 --topic _HOATLASOK
kafka-topics --zookeeper master:2181 --create --replication-factor 1 --partitions 3 --topic ATLAS_ENTITIES
kafka-topics --zookeeper master:2181 --create --replication-factor 1 --partitions 3 --topic ATLAS_HOOK


kafka-topics --zookeeper master:2181 --delete --topic _HOATLASOK
kafka-topics --zookeeper master:2181 --delete --topic ATLAS_ENTITIES
kafka-topics --zookeeper master:2181 --delete --topic ATLAS_HOOK
kafka-topics --zookeeper master:2181 --delete --topic __consumer_offsets
mvn -T compile package -DskipTests=true
cp ./target/searchbox-suggester-1.0-SNAPSHOT-jar-with-dependencies.jar /salsasvn/projects/apache-solr-4.0.0/example/solr/solrLib
cp ./target/searchbox-suggester-1.0-SNAPSHOT-jar-with-dependencies.jar /salsasvn/searchbox-server/solr/solrLib

mvn -T compile package -DskipTests=true
cp ./target/searchbox-suggester-1.1-jar-with-dependencies.jar /projects/solr-4.1.0/example/solr/solrLib
cp ./target/searchbox-suggester-1.1-jar-with-dependencies.jar /searchbox-server/solr/solrLib
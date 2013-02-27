mvn -T compile package -DskipTests=true
mkdir ../deploy-dir/searchbox-suggester
cp ./target/searchbox-suggester-1.0-SNAPSHOT-jar-with-dependencies.jar  ../deploy-dir/searchbox-suggester/searchbox-suggester-1.38.jar
cp README*  ../deploy-dir/searchbox-suggester
cd ../deploy-dir/searchbox-suggester
zip searchbox-suggester.zip *
mv *.zip ..
cd ..
rm -rf searchbox-suggester/
cd /salsasvn/searchbox-suggester


clojure -X:jar :jar Mycelium.jar
mvn deploy:deploy-file -Dfile=mycelium.jar -DpomFile=pom.xml -DrepositoryId=clojars -Durl=https://clojars.org/repo/

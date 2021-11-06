clojure -A:jar mycelium.jar
mvn deploy:deploy-file -Dfile=mycelium.jar -DpomFile=pom.xml -DrepositoryId=clojars -Durl=https://clojars.org/repo/

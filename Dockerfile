FROM tomcat:10

COPY target/nanopub-query.war /usr/local/tomcat/webapps/ROOT.war

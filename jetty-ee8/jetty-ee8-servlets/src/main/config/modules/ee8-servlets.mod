# DO NOT EDIT THIS FILE - See: https://jetty.org/docs/

[description]
Adds Jetty EE8 utility servlets and filters available to a webapp.
Puts org.eclipse.jetty.ee8.servlets on the server classpath
(CrossOriginFilter, DosFilter, MultiPartFilter, QoSFilter, etc.)
for use by all web applications.

[environment]
ee8

[depend]
ee8-servlet

[lib]
lib/jetty-ee8-servlets-${jetty.version}.jar


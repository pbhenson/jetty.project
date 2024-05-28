# DO NOT EDIT THIS FILE - See: https://eclipse.dev/jetty/documentation/

[description]
Demo JNDI Resources Webapp

[environment]
ee11

[tags]
demo
webapp

[depends]
ee11-deploy
ext
jdbc
ee11-plus
ee11-jndi
ee11-demo-mock-resources

[files]
basehome:modules/demo.d/ee11-demo-jndi.xml|webapps/ee11-demo-jndi.xml
maven://org.eclipse.jetty.demos/jetty-servlet5-demo-jndi-webapp/${jetty.version}/war|webapps/ee11-demo-jndi.war
maven://jakarta.mail/jakarta.mail-api/@ee11.jakarta.mail.api.version@/jar|lib/ee11/jakarta.mail-api-@ee11.jakarta.mail.api.version@.jar
maven://jakarta.activation/jakarta.activation-api/@ee11.jakarta.activation.api.version@/jar|lib/ee11/jakarta.activation-api-@ee11.jakarta.activation.api.version@.jar

[lib]
lib/ee11/jakarta.mail-api-@ee11.jakarta.mail.api.version@.jar
lib/ee11/jakarta.activation-api-@ee11.jakarta.activation.api.version@.jar

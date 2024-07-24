# DO NOT EDIT THIS FILE - See: https://eclipse.dev/jetty/documentation/

[description]
Demo Jetty WebSocket Webapp

[environment]
ee11

[tags]
demo
webapp

[depends]
ee11-deploy
ext
ee11-websocket-jetty

[files]
basehome:modules/demo.d/ee11-demo-jetty-websocket.xml|webapps/ee11-demo-jetty-websocket.xml
basehome:modules/demo.d/ee11-demo-jetty-websocket.properties|webapps/ee11-demo-jetty-websocket.properties
maven://org.eclipse.jetty.demos/jetty-ee11-demo-jetty-websocket/webapp/${jetty.version}/war|webapps/ee11-demo-jetty-websocket.war

# About
This is a Red5 cluster-plugin that can work in red5 1.0.10.And will update with Red5 version.You can see also:

Red5 https://github.com/Red5/red5-server

Red5-plugin-cluster https://github.com/Red5/red5-plugins/tree/master/cluster


Red5 cluster-plugin in Red5 Offical website is not work anymore. if you want stable or bussiness support for this funciton,you can view http://www.red5pro.com.
I don't want to pay(^^),so I made some change from Red5 Office version to make it work again in new version of red5(1.0.10).

I also release a version of Red5-server that can Work in Origin-Edge Mode.If you don't want to write code,you can download the release version.

# Relase version


# Just Use

1. Download the release version
2. Unzip,and you will got two folders.
3. red5-server is orgin server
4. red5-server-edge is edge server
5. modify red5-server/conf/red5.properties，change MRTMP setting.

mrtmp.host=192.168.0.4
mrtmp.server=192.168.0.4
mrtmp.port=9035
mrtmp.event_threads_core=4
mrtmp.event_threads_max=32
mrtmp.event_threads_queue=0
mrtmp.event_threads_keepalive=60

mrtmp.send_buffer_size=271360
mrtmp.receive_buffer_size=65536
mrtmp.ping_interval=5000
mrtmp.max_inactivity=60000
mrtmp.tcp_nodelay=true

you just need change 192.168.0.4 in mrtmp.host and mrtmp.server to your ip address.


6. modify red5-server-edge/conf/red5.properties,change MRTMP setting like step 5.And please notice,mrtmp.host And mrtmp.server will be the origin ip.If you test it on your own computer,it will be same with step5.
7. Launch the origin-server and edge-server
8. Connect Orgin with port 1935(RTMP default port)
9. Connect Edge with port 19350
10. You can test in rtmp://your.ip:19350/live,this application is in origin-server,but you can connect it with edge-server.


enjoy~

# Build

1. Download source code.
2. If you are in Window.run build.bat to build the project.
3. Copy target/cluster-1.0.8-M1.jar to plugin folder in your red5-server.I just test this in red5 1.0.10.
4. Copy resources/red5-origin-core.xml to your red5-server(origin)/conf,REPLACE your red5-core.xml
5. Copy resources/red5-edge-core.xml to your red5-server(edge)/conf,REPLACE your red5-core.xml
6. Copy content in resource/cluster.properties to your red5-server(origin/edge),and setting just like step5 and step6 in "Just Use" part.

Ps.If you let red5-server/red5-server-edge in parent folder of cluster-plugin.you can run run.bat to fast build and copy.Please make sure red5 server is shutdown when you run copy function with run.bat

# Contect With Me

You can post your message with Issue,or Email me: D5@microgame.cn

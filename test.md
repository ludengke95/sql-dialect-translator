mysql client 5 测试数据库是否连通：wslc run -it --rm mysql:5.7 mysql -h host.docker.internal -P 3306 -u root -p123 -e"select 1;"
mysql client 8 测试数据库是否连通：wslc run -it --rm mysql:8 mysql -h host.docker.internal -P 3306 -u root -p123 -e"select 1;"
mysql jdbc 5 测试数据库是否连通：wslc run -it --rm mysql5-sqlline -u "jdbc:mysql://host.docker.internal:3306/tes?useSSL=false&characterEncoding=utf8" -n root -p 123 -e "select 1;"
mysql jdbc 8 测试数据库是否连通：wslc run -it --rm mysql8-sqlline -u "jdbc:mysql://host.docker.internal:3306/tes?useSSL=false&characterEncoding=utf8" -n root -p 123 -e "select 1;"

现在服务前两条成功，后两条失败，你改一下，直到sdtp server服务下四个都执行成功

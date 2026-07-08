db=mysql
driver=${MYSQL_DRIVER}
conn=jdbc:mysql://${SDTP_HOST}:${SDTP_PORT}/${PG_DB}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
user=${SDTP_USER}
password=${SDTP_PASSWORD}

warehouses=2
loadWorkers=4

terminals=2
runMins=3

# TPC-C Transaction Weights
newOrderWeight=45
paymentWeight=43
orderStatusWeight=4
deliveryWeight=4
stockLevelWeight=4

runTxnsPerTerminal=0
limitTxnsPerMin=0

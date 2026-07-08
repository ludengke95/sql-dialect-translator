db=postgres
driver=org.postgresql.Driver
conn=jdbc:postgresql://${PG_HOST}:${PG_PORT}/${PG_DB}
user=${PG_USER}
password=${PG_PASSWORD}

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

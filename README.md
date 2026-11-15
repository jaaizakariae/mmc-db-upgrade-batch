# mmc-db-upgrade-batch

Batch de mise à niveau de la base de données du projet MMC (Monitoring Continu du Marché de Change).

## Program arguments

--spring.profiles.active=local

## VM arguments

### macOS

-Dapp.paths.configRoot=/Users/mac/Documents/apps/mmc/batchs/mmc-db-upgrade-batch/config -Dapp.paths.appRoot=/Users/mac/Documents/apps/mmc/batchs/mmc-db-upgrade-batch
-Dspring.devtools.restart.enabled=false

### Windows

-Dapp.paths.configRoot=E:/apps/mmc/batchs/mmc-db-upgrade-batch/config

-Dapp.paths.appRoot=E:/apps/mmc/batchs/mmc-db-upgrade-batch

-Dspring.devtools.restart.enabled=false

## Build

mvn clean package
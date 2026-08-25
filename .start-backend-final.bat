@echo off
setlocal enabledelayedexpansion
set JAVA=D:\Java\tools\jdk-21.0.11+10\bin\java.exe
set JARBASE=D:\Desktop\amazon-erp\amz-service
set LOGDIR=D:\Desktop\amazon-erp\.service-logs
if not exist "%LOGDIR%" mkdir "%LOGDIR%"

set COMMON=-Xms256m -Xmx512m -Dspring.cloud.nacos.discovery.enabled=false -Dspring.cloud.nacos.config.enabled=false -DSEATA_ENABLED=false
set JWT=--spring.flyway.baseline-on-migrate=true --crypto.key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY= --jwt.secret-key=local-e2e-secret-key-0123456789abcdef
set CRYPTO=--AMZ_CRYPTO_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=
set INFRA=--spring.redis.host=127.0.0.1 --spring.redis.port=6379 --spring.redis.password=amzpassamzpass --spring.data.redis.host=127.0.0.1 --spring.data.redis.port=6379 --spring.data.redis.password=amzpass --spring.rabbitmq.host=127.0.0.1

start "user" /min cmd /c ""%JAVA%" %COMMON% -jar %JARBASE%\amz-service-user\target\amz-service-user-1.0-SNAPSHOT.jar --server.port=8080 --spring.datasource.url="jdbc:mysql://127.0.0.1:3307/amz_user?serverTimezone=Asia/Shanghai^&useSSL=false^&allowPublicKeyRetrieval=true" --spring.datasource.username=root --spring.datasource.password= %JWT% %CRYPTO% %INFRA% > "%LOGDIR%\user.log" 2>&1"
echo LAUNCHED user

start "order" /min cmd /c ""%JAVA%" %COMMON% -jar %JARBASE%\amz-service-order\target\amz-service-order-1.0-SNAPSHOT.jar --server.port=8105 --spring.datasource.dynamic.datasource.master.url="jdbc:mysql://127.0.0.1:3307/amz_order?serverTimezone=Asia/Shanghai^&useSSL=false^&allowPublicKeyRetrieval=true" --spring.datasource.dynamic.datasource.slave.url="jdbc:mysql://127.0.0.1:3307/amz_order?serverTimezone=Asia/Shanghai^&useSSL=false^&allowPublicKeyRetrieval=true" --spring.datasource.dynamic.datasource.master.username=root --spring.datasource.dynamic.datasource.slave.username=root --spring.datasource.dynamic.datasource.master.password= --spring.datasource.dynamic.datasource.slave.password= %JWT% %CRYPTO% %INFRA% > "%LOGDIR%\order.log" 2>&1"
echo LAUNCHED order

start "report" /min cmd /c ""%JAVA%" %COMMON% -jar %JARBASE%\amz-service-report\target\amz-service-report-1.0-SNAPSHOT.jar --server.port=8102 --spring.datasource.url="jdbc:mysql://127.0.0.1:3307/amz_report?serverTimezone=Asia/Shanghai^&useSSL=false^&allowPublicKeyRetrieval=true" --spring.datasource.username=root --spring.datasource.password= %JWT% %CRYPTO% %INFRA% > "%LOGDIR%\report.log" 2>&1"
echo LAUNCHED report

start "spapi" /min cmd /c ""%JAVA%" %COMMON% -jar %JARBASE%\amz-service-spapi\target\amz-service-spapi-1.0-SNAPSHOT.jar --spring.autoconfigure.exclude=com.alibaba.cloud.sentinel.SentinelWebAutoConfiguration,com.alibaba.cloud.sentinel.SentinelProtectionAutoConfiguration --server.port=8096 --spring.datasource.dynamic.datasource.master.url="jdbc:mysql://127.0.0.1:3307/amz_spapi?serverTimezone=Asia/Shanghai^&useSSL=false^&allowPublicKeyRetrieval=true" --spring.datasource.dynamic.datasource.slave.url="jdbc:mysql://127.0.0.1:3307/amz_spapi?serverTimezone=Asia/Shanghai^&useSSL=false^&allowPublicKeyRetrieval=true" --spring.datasource.dynamic.datasource.master.username=root --spring.datasource.dynamic.datasource.slave.username=root --spring.datasource.dynamic.datasource.master.password= --spring.datasource.dynamic.datasource.slave.password= %JWT% %CRYPTO% %INFRA% > "%LOGDIR%\spapi.log" 2>&1"
echo LAUNCHED spapi

start "ad" /min cmd /c ""%JAVA%" %COMMON% -jar %JARBASE%\amz-service-ad\target\amz-service-ad-1.0-SNAPSHOT.jar --server.port=8097 --spring.datasource.url="jdbc:mysql://127.0.0.1:3307/amz_ad?serverTimezone=Asia/Shanghai^&useSSL=false^&allowPublicKeyRetrieval=true" --spring.datasource.username=root --spring.datasource.password= %JWT% %CRYPTO% %INFRA% > "%LOGDIR%\ad.log" 2>&1"
echo LAUNCHED ad

start "finance" /min cmd /c ""%JAVA%" %COMMON% -jar %JARBASE%\amz-service-finance\target\amz-service-finance-1.0-SNAPSHOT.jar --server.port=8103 --spring.datasource.url="jdbc:mysql://127.0.0.1:3307/amz_finance?serverTimezone=Asia/Shanghai^&useSSL=false^&allowPublicKeyRetrieval=true" --spring.datasource.username=root --spring.datasource.password= %JWT% %CRYPTO% %INFRA% > "%LOGDIR%\finance.log" 2>&1"
echo LAUNCHED finance

start "ops" /min cmd /c ""%JAVA%" %COMMON% -jar %JARBASE%\amz-service-ops\target\amz-service-ops-1.0-SNAPSHOT.jar --server.port=8101 --spring.datasource.url="jdbc:mysql://127.0.0.1:3307/amz_ops?serverTimezone=Asia/Shanghai^&useSSL=false^&allowPublicKeyRetrieval=true" --spring.datasource.username=root --spring.datasource.password= %JWT% %CRYPTO% %INFRA% > "%LOGDIR%\ops.log" 2>&1"
echo LAUNCHED ops

start "logistics" /min cmd /c ""%JAVA%" %COMMON% -jar %JARBASE%\amz-service-logistics\target\amz-service-logistics-1.0-SNAPSHOT.jar --server.port=8100 --spring.datasource.url="jdbc:mysql://127.0.0.1:3307/amz_logistics?serverTimezone=Asia/Shanghai^&useSSL=false^&allowPublicKeyRetrieval=true" --spring.datasource.username=root --spring.datasource.password= %JWT% %CRYPTO% %INFRA% > "%LOGDIR%\logistics.log" 2>&1"
echo LAUNCHED logistics

start "procurement" /min cmd /c ""%JAVA%" %COMMON% -jar %JARBASE%\amz-service-procurement\target\amz-service-procurement-1.0-SNAPSHOT.jar --server.port=8098 --spring.datasource.url="jdbc:mysql://127.0.0.1:3307/amz_procurement?serverTimezone=Asia/Shanghai^&useSSL=false^&allowPublicKeyRetrieval=true" --spring.datasource.username=root --spring.datasource.password= %JWT% %CRYPTO% %INFRA% > "%LOGDIR%\procurement.log" 2>&1"
echo LAUNCHED procurement

start "customer" /min cmd /c ""%JAVA%" %COMMON% -jar %JARBASE%\amz-service-customer\target\amz-service-customer-1.0-SNAPSHOT.jar --server.port=8099 --spring.datasource.url="jdbc:mysql://127.0.0.1:3307/amz_customer?serverTimezone=Asia/Shanghai^&useSSL=false^&allowPublicKeyRetrieval=true" --spring.datasource.username=root --spring.datasource.password= %JWT% %CRYPTO% %INFRA% > "%LOGDIR%\customer.log" 2>&1"
echo LAUNCHED customer

start "ai" /min cmd /c ""%JAVA%" %COMMON% -jar %JARBASE%\amz-service-ai\target\amz-service-ai-1.0-SNAPSHOT.jar --spring.autoconfigure.exclude=com.alibaba.cloud.sentinel.SentinelWebAutoConfiguration,com.alibaba.cloud.sentinel.SentinelProtectionAutoConfiguration --server.port=8091 --spring.datasource.url="jdbc:mysql://127.0.0.1:3307/amz_ai?serverTimezone=Asia/Shanghai^&useSSL=false^&allowPublicKeyRetrieval=true" --spring.datasource.username=root --spring.datasource.password= --deepseek.api-key=dummy-local-key %JWT% %CRYPTO% %INFRA% > "%LOGDIR%\ai.log" 2>&1"
echo LAUNCHED ai

start "product" /min cmd /c ""%JAVA%" %COMMON% -jar %JARBASE%\amz-service-product\target\amz-service-product-1.0-SNAPSHOT.jar --spring.data.mongodb.password=x --server.port=8095 --spring.datasource.url="jdbc:mysql://127.0.0.1:3307/amz_product?serverTimezone=Asia/Shanghai^&useSSL=false^&allowPublicKeyRetrieval=true" --spring.datasource.username=root --spring.datasource.password= %JWT% %CRYPTO% %INFRA% > "%LOGDIR%\product.log" 2>&1"
echo LAUNCHED product

start "search" /min cmd /c ""%JAVA%" %COMMON% -jar %JARBASE%\amz-service-search\target\amz-service-search-1.0-SNAPSHOT.jar --server.port=8090 --spring.datasource.url="jdbc:mysql://127.0.0.1:3307/amz_search?serverTimezone=Asia/Shanghai^&useSSL=false^&allowPublicKeyRetrieval=true" --spring.datasource.username=root --spring.datasource.password= --spring.elasticsearch.uris=http://127.0.0.1:9200 %JWT% %CRYPTO% %INFRA% > "%LOGDIR%\search.log" 2>&1"
echo LAUNCHED search

start "message" /min cmd /c ""%JAVA%" %COMMON% -jar %JARBASE%\amz-service-message\target\amz-service-message-1.0-SNAPSHOT.jar --server.port=8889 %JWT% %CRYPTO% %INFRA% > "%LOGDIR%\message.log" 2>&1"
echo LAUNCHED message

echo ALL_LAUNCHED










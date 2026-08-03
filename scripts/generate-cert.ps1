# Generate self-signed certificate for development/testing
$certPath = Join-Path $PSScriptRoot ".." "amz-gateway" "src" "main" "resources" "keystore.p12"
$password = if ($env:SSL_KEY_STORE_PASSWORD) { $env:SSL_KEY_STORE_PASSWORD } else { "changeit" }

keytool -genkeypair -alias amz-erp -keyalg RSA -keysize 2048 `
  -storetype PKCS12 -keystore $certPath -validity 365 `
  -storepass $password -keypass $password `
  -dname "CN=localhost, OU=Dev, O=AmzERP, L=Beijing, ST=Beijing, C=CN"

Write-Output "Certificate generated: $certPath"

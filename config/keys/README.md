# 演示签名密钥

此目录仅放你自己生成的演示证书，原项目的签名证书不随展示包分发。

```powershell
keytool -genkeypair -alias showcase-signing -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore config/keys/demo-signing.p12 -validity 365 -dname "CN=eMaker Agent Demo"
```

按提示设置新密码，并把 JWT_KEYSTORE_LOCATION、JWT_KEYSTORE_PASSWORD、JWT_KEY_PASSWORD、JWT_KEY_ALIAS 配入运行环境。不同服务应使用匹配的演示密钥。证书文件已被 Git 忽略。

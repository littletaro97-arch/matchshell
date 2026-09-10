# MatchShell GitHub 稳定版发布

## 固定身份

- applicationId: `com.hcgy2018.site`
- 首个稳定版：`1.0.0` / versionCode `100`
- APK 签名证书必须永久保持一致。
- 更新清单使用独立 RSA 私钥签名；APP 仅内置公钥。

现有 `.pdf` 包名或 Debug 签名安装包不能覆盖升级到正式版。首轮需要卸载测试包并安装正式版，之后才能使用 APP 内更新。

## GitHub Secrets

- `MATCHSHELL_KEYSTORE_BASE64`
- `MATCHSHELL_KEYSTORE_PASSWORD`
- `MATCHSHELL_KEY_ALIAS`
- `MATCHSHELL_KEY_PASSWORD`
- `UPDATE_SIGNING_PRIVATE_KEY_PEM`

私钥和 keystore 不得提交到 Git。发布工作流通过 GitHub Actions 的 `Publish stable APK` 手动触发，输入版本名称、递增 versionCode、发布说明、灰度比例、最低支持版本和强制更新开关。

## 客户端策略

- 启动 3 秒后后台检查，每 24 小时最多自动检查一次。
- 功能菜单提供手动检查。
- 自动检查尊重 `rolloutPercent`；手动检查绕过灰度筛选。
- `mandatory=true` 或当前版本低于 `minimumSupportedVersionCode` 时不可选择稍后提醒。
- 下载后验证更新清单签名、APK SHA-256、包名、versionCode 和签名证书，再交给 Android 系统安装器。

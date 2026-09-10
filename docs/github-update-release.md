# MatchShell GitHub 构建 + Gitee 下载发布

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
- `GITEE_ACCESS_TOKEN`：仅授予目标 Gitee 仓库所需权限，不写入 Git 或 APK。
- `GITEE_USERNAME`：Gitee 登录用户名；组织仓库也填写实际执行推送的个人用户名。

## GitHub Actions Variables

- `GITEE_OWNER`：Gitee 个人或组织的空间地址，不是显示昵称。
- `GITEE_REPOSITORY`：Gitee 仓库路径，建议保持为 `matchshell`。

私钥、keystore 和 Gitee 令牌不得提交到 Git。发布工作流通过 GitHub Actions 的 `Publish stable APK` 手动触发，输入版本名称、递增 versionCode、发布说明、灰度比例、最低支持版本和强制更新开关。

GitHub 工作流负责构建、签名并创建 GitHub Release，然后把对应标签同步到 Gitee。Gitee Go 读取 `.workflow/gitee-release.yml`，在国内执行 `tools/publish-gitee-release.sh`：下载已签名 APK、幂等创建或复用 Gitee Release，并上传附件。GitHub 工作流只轮询 Gitee 附件；附件出现后才生成并部署签名更新清单，其中 `apkUrl` 使用 Gitee 的真实下载地址。

Gitee Go 首次使用需要在仓库网页中开通流水线，并把 `GITEE_ACCESS_TOKEN` 配置为加密环境变量。不得把令牌直接写入 YAML 或仓库。流水线由 `v*` 标签触发；上传脚本带连接/总超时、有限重试和重复附件检测。若 Gitee 上传失败，GitHub Pages 上的稳定清单保持上一版本。

## 首次接入 Gitee

1. 在 Gitee 使用“从 GitHub 导入仓库”，来源选择 `https://github.com/littletaro97-arch/matchshell`。
2. 确认 Gitee 仓库的真实空间地址和仓库路径后，在本地添加名为 `gitee` 的远程仓库；保留现有 `origin` 指向 GitHub。
3. 在 GitHub 仓库 Actions Variables 中设置 `GITEE_OWNER`、`GITEE_REPOSITORY`，在 Actions Secrets 中设置 `GITEE_USERNAME`、`GITEE_ACCESS_TOKEN`。
4. 首次正式发布前先验证 Gitee 仓库已包含目标提交。Gitee Release API 的 `target_commitish` 必须能在目标仓库解析。

账号绑定只代表 Gitee 能获得 GitHub 授权，不会自动创建同名仓库，也不会自动配置本机 Git 凭据。

## 客户端策略

- 启动 3 秒后后台检查，每 24 小时最多自动检查一次。
- 功能菜单提供手动检查。
- 自动检查尊重 `rolloutPercent`；手动检查绕过灰度筛选。
- `mandatory=true` 或当前版本低于 `minimumSupportedVersionCode` 时不可选择稍后提醒。
- 下载后验证更新清单签名、APK SHA-256、包名、versionCode 和签名证书，再交给 Android 系统安装器。

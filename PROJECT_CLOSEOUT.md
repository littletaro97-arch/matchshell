# MatchShell：项目收尾总结

收尾日期：2026-11-04。

## 结论

本项目结束维护，不再更新。最终交付版本为 **v1.1.4**（versionCode 114）。保留现有源码与安装包，不另发“收尾版”，不更换已有 APK 或签名。

火柴公益网站的 Android 全屏 WebView 承载与调试工具，内置资源预处理和文件池。

## 最终下载

- [直接下载 APK](https://github.com/littletaro97-arch/matchshell/releases/download/v1.1.4/matchshell-v1.1.4-arm64.apk)
- [最终 Release](https://github.com/littletaro97-arch/matchshell/releases/tag/v1.1.4)
- 文件名：`matchshell-v1.1.4-arm64.apk`
- GitHub 记录的 SHA-256：`eddd5dbfb6f9e882dc9882f6ef3b5aa753552e4526b1cd95701b83c582175141`

需要 Android 8.0 或更高版本及 arm64-v8a 设备。请下载现有正式 APK，不要下载源码 ZIP 当作安装包。Debug 证书或旧 `.pdf` 包名的测试包不能作为同一正式应用直接覆盖升级；卸载前先处理文件池中的文件。

## 已完成范围

- 沉浸式网页浏览、返回键与网站 JS 桥接、文件上传下载、壳标识及安全区注入。
- 照片压缩、视频转码、arm64 端侧 DOCX/PPTX/XLSX 转 PDF。
- 文件池导入 PDF、多选、重命名与删除，并支持从文件池向网页上传。
- 应用内签名清单检查、后台下载及 APK 完整性和签名校验。

## 已知限制

- 最终 APK 仅适用于 arm64 设备，最低 Android 8.0。
- 依赖上游网站可用性与接口兼容性；停止本应用更新不等于停止或冻结网站项目。
- 文件池是中转站，应用卸载会清除其中的本地文件。
- Office 复杂排版转换仍存在待实机验证的兼容性边界。

停更后不再承诺新功能、问题修复或未来系统与第三方服务的兼容性。历史文档中的下一版计划、待办及发布流程仅供参考，不构成继续开发的安排。

## 下载与更新链保留

客户端使用 GitHub Pages 的 `updates/stable.json` 及其签名，再按清单地址下载 APK。仓库现有发布工作流生成 GitHub Release 下载地址；旧 README 曾写为 Gitee，不能据此推断线上清单当前地址。保留最终 GitHub Release、Pages 站点与已签名清单。本次未重发清单，也未确认线上清单的当前内容。

本次收尾不删除 Git 标签、提交历史或源码；旧 Release 清理单独处理，本文不表示旧版已删除。

## 验证边界与历史资料

本次核对 README、Gradle 配置、Manifest、发布工作流与 GitHub Release 元数据，仅修改文档，未重新构建、重签名或进行 Android 真机验收。

详情见 [UPSTREAM_CONTRACT.md](UPSTREAM_CONTRACT.md)、[发布机制](docs/github-update-release.md) 与 [v1.1.4 Release](https://github.com/littletaro97-arch/matchshell/releases/tag/v1.1.4)。

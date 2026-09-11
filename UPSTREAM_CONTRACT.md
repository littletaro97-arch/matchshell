# MatchShell 上游网站承载契约 v1

- 契约 ID：`MSC-20260905-01`
- 本项目：`E:\课外项目\matchshell`
- 上游权威契约：`E:\火柴公益官网建设-全新架构\docs\contracts\matchshell-carrier-contract-v1.md`
- 生产入口：`https://hcgy2018.site/`（历史生产参照 v0.8.12，本轮未在线复验）
- 当前网站维护目录：`E:\火柴公益官网建设-全新架构`；当前提交以网站 Git HEAD 与启动窗口为准。
- 迁移前只读来源：`E:\火柴公益-长期项目\.worktrees\v0.8.13.2.1` / `5bcd3f03d151e6892a885f3168354493f0d2f706`

MIG-002（2026-09-05）只更新关联与联调说明；本壳仍独立维护，未改变默认生产 URL、Android 代码或 APK。

## 新网站本地入口

双击 `E:\火柴公益官网建设-全新架构\启动手机联调.bat`，选择与设备同一 Wi-Fi 的电脑 IPv4，然后长按本壳右上角刷新按钮，填入窗口显示的完整 `http://IPv4:8880/`。不要照抄旧 IP/8765；普通本机入口只监听 127.0.0.1，手机不能使用。

网站的 `docs/LOCAL_ACCEPTANCE.md` 记录联调核验方法。AGENTS 中 PKT110 与网站旧计划 PTK110 存在差异，设备型号、WebView 和壳实际保存的 URL 均现场核对，不能把历史记录当作实时状态。

## 本项目的责任

MatchShell 是火柴公益网站的独立 Android WebView 承载端，不是网站源码或数据的副本。它只通过 URL 承载网站页面，并负责 WebView 视口、系统栏、返回、文件选择、Cookie 下载、外部跳转和设备侧调试。

不得读取、复制或共享上游的源码、SQLite、会话、资源原件、日志、备份、密钥或构建目录。网站版本升级不自动要求重建 APK；只有正式 URL、协议/端口、WebView 兼容要求、跨主机导航、登录、上传下载或安全策略变化时，才需要按上游契约联调。

## 联调规则

- 默认地址只指向生产 HTTPS URL。候选工作树不是手机可访问地址；不要把 `127.0.0.1` 填入平板。
- LAN HTTP 仅用于经网站端明确配置的临时调试：实际可达 LAN 地址、`ALLOWED_HOSTS`、HTTPS 重定向与 Cookie 设置必须一致。
- 每次联调记录 APK SHA-256、网站版本/commit、设备、WebView provider 版本和首页/资源页/登录/上传下载结果。
- APK 本地构建与网站自动化不等于设备验收，也不授权服务器部署或 Release。

## 壳标识与安全区约定（2026-09-10 起，2026-09-11 修订）

网站如需为壳渲染 APP 模式，按下面两条契约对接：

1. **判定是否在壳内**：请求 UA 末尾是否含 `MatchShell/<版本名>`（如 `MatchShell/1.1.2`）。
   服务端读到即可在模板输出 `data-app-mode`，首屏生效、不依赖 JS。
   客户端也可用 `typeof window.MatchShell !== "undefined"` 判断。
2. **安全区变量**：壳会在每个页面的 `<html>` 上写入 `--ms-safe-top` / `--ms-safe-bottom` /
   `--ms-safe-left` / `--ms-safe-right`（CSS px，随旋转和挖孔自动更新）。

### ⚠️ 底部避让已由壳在视口层兜底，网站不要再对底部加内边距

壳会给 WebView 留出等于底部安全区的高度，网站视口底边本身就落在手势条之上，
贴底固定元素（如预览页翻页底栏）天然不会被压住。

这样做的原因是网站侧一个既有问题：`src/templates/base.html` 的 viewport
未声明 `viewport-fit=cover`，按 CSS 规范此时 `env(safe-area-inset-*)` **恒为 0**，
所以网站 `resource-preview-layout.css` / `resource-browser-preview.css` /
`notifications.css` 里已有的安全区写法目前实际是空转的。

因此：

- 网站**不要**再补 `viewport-fit=cover`，也**不要**对底部再加 `padding-bottom`
  （无论用 `env(safe-area-inset-bottom)` 还是 `--ms-safe-bottom`）——
  会与壳的兜底叠加成双重内边距。
- 网站**可以**继续用 `--ms-safe-top` / `-left` / `-right` 处理刘海与侧边挖孔：
  壳在顶部与左右**不**留白，全屏内容延伸至刘海区域是有意的观感选择。
- 若某页面仍出现"底部内容被手势条盖住"，先记录设备型号与复现路径，
  在**壳侧**调整兜底策略，不要在网站侧临时加内边距。

以上变量与 UA token 由壳单向提供给网站；网站不得假设壳会读取任何页面 DOM 或 Cookie 来反向判断。

### 两侧契约副本的同步状态

上游权威副本 `E:\火柴公益官网建设-全新架构\docs\contracts\matchshell-carrier-contract-v1.md`
仍是 2026-09-05 的 v1，**尚未包含本节的壳标识与安全区约定**（网站侧因此没有实现 APP 模式渲染）。
按本节开头第 1 条对接前，先把本节同步到上游副本。

## 上游变更处理

先查看上游契约，再评估 MatchShell 是否需要改动；不要通过软链接、复制网站目录或直接读取网站运行数据来“同步”。

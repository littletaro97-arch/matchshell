# MatchShell 探索报告：角色首页 + 移动端独立 UI

> 状态：仅探索，未实现。本次评估基于 `E:\火柴公益官网建设-全新架构\src\core\urls.py` 与 `views.py` 的现有路由/权限模型。

---

## 1. 不同身份用户进入 APP 时展示不同首页

### 1.1 业务目标

- 游客 → 网站首页（`/` 即 `home`）
- 普通成员 → 资源首页（`resource_list` 或 `member_home`）
- 管理员 → 后台总览（`admin_dashboard`）

### 1.2 推荐方案：由网站 `/` 根据角色重定向（改动最小、最可靠）

当前 `home` 视图对所有人返回 `home.html`。可把它改成入口调度器：

```python
def home(request):
    if request.user.is_authenticated:
        if request.user.is_site_admin:
            return redirect("admin_dashboard")
        return redirect("member_home")
    # 保持游客首页
    latest = [...]
    return render(request, "home.html", {...})
```

**为什么推荐：**

1. **APP 不用改**。壳仍然只加载 `https://hcgy2018.site/`，网站自己决定最终页面。这与现有 `management_hub` 的"按角色分发"设计一致。
2. **状态唯一真实源**。登录态在服务端 Cookie/Session 里，APP 读取 Cookie 无法直接解析角色，与其让 APP 猜，不如让服务端 redirect。
3. **登录/登出/切换账号自动生效**。用户退出后回到 `/`，网站再次判断为游客；管理员被降级为成员后，访问 `/` 自动到 `member_home`。
4. **SEO/书签不破坏**。`/admin_dashboard`、`/member_home`、`/resource_list` 这些 URL 照常可用；`/login` 登录后回到 `/` 也能正确分发。

**需要上游网站配合：**

- 修改 `src/core/views.py` 的 `home` 函数（上面代码）。
- 检查 `home.html` 是否只在游客场景需要；管理员/成员不再看到它，除非显式导航回 `/`。
- 如果担心已登录用户偶尔需要看"营销首页"，可在顶部导航保留一个"回到首页"链接指向 `/`；但重定向后他们点这个链接也会再被转走。如需保留访问，可新增 `/welcome/` 或 `/about/` 作为独立公共页。

### 1.3 备选方案 A：APP 通过 JS 读取角色后跳转

在 `base.html` 上加一个数据属性：

```html
<body data-user-role="{% if user.is_site_admin %}admin{% elif user.is_authenticated %}member{% else %}guest{% endif %}">
```

然后在 APP 的 `onPageFinished` 里读取：

```kotlin
web.evaluateJavascript(
    "document.body.dataset.userRole",
) { role ->
    when (role?.trim('"')) {
        "member" -> if (!currentUrl().contains("/member/")) web.loadUrl(base + "member/")
        "admin"  -> if (!currentUrl().contains("/management/admin/")) web.loadUrl(base + "management/admin/")
    }
}
```

**问题：**

- 必须等首页加载完才能判断角色，会先闪一下游客首页再跳转，体验差。
- 角色变化时（登出/切换账号）需要重新读取，容易遗漏。
- 不同域名调试时 `/member/` 等路径可能不存在，需要额外逻辑。
- 服务端 redirect 只需改一处，这个方案要改网站模板 + APP 两处。

**结论：只在"必须把首页渲染权留在 APP"时才考虑，否则不推荐。**

### 1.4 备选方案 B：APP 先调 `/api/me` 接口

网站新增一个接口返回 `{role: "guest|member|admin"}`，APP 启动时调用它再决定加载哪个 URL。

**问题：**

- 多一次网络请求，冷启动变慢。
- 需要处理未登录 / Cookie 未同步 / CSRF 等情况。
- 接口需要在 `ALLOWED_HOSTS`、CORS/CSRF 上与 APP 对齐。
- 仍然不如服务端 redirect 直接。

### 1.5 风险与反例

| 风险 | 说明 |
|---|---|
| 重定向后无法回看游客首页 | 对登录用户，`/` 不再是营销页。若业务需要保留，需单独做一个 `/about/` 或 `/welcome/`。 |
| 本地调试地址没有成员/管理员账号 | 调试时一般只有游客首页可测；重定向不影响，因为未登录时仍走 `home`。 |
| 已保存书签失效 | `/member/` 和 `/management/admin/` 仍可直达，不受影响。 |
| App Store 审核 | 如果未来上架，审核员用游客身份打开看到的是正常首页，符合预期。 |

### 1.6 建议落地顺序

1. 在上游网站 `home` 视图加入角色重定向。
2. MatchShell 不用改代码，直接验证：游客、成员、管理员分别打开 APP，看最终落点。
3. 若验证通过，将这一行为写进 `UPSTREAM_CONTRACT.md`。

---

## 2. 移动端是否要实现一套独立 UI

### 2.1 问题本质

用户希望 APP 端有"原生级"交互，而不是直接套网站的响应式布局。

### 2.2 推荐方案：先让网站为壳提供一套"APP 模式"（成本最低、效果最好）

MatchShell 在 WebView 加载时带上一个自定义 User-Agent，例如：

```kotlin
val originalUA = web.settings.userAgentString
web.settings.userAgentString = "$originalUA MatchShell/0.1.0"
```

网站侧检测到 `MatchShell` 后，可以：

- 隐藏顶部 `site-header`（APP 已经全屏，不需要浏览器式导航）。
- 把关键操作（资源、管理、个人）改成底部固定导航条，更接近原生 APP。
- 放大触摸目标、调整字体/间距、禁用悬停态。

**实现要点：**

1. APP 改 1 行代码（UA）。
2. 网站新增一个 `app-mode.css`，通过 `@media` 或 `html[data-app-mode]` 生效。
3. 通过 JS 暴露 `window.isMatchShell = true`，让页面可以调用 `MatchShell.setBackHandler` 等桥接。

**为什么推荐：**

- 功能"不多不少"由网站保证，不需要在 Android 端重写任何业务逻辑。
- 一套 HTML/CSS 同时服务移动端浏览器和 APP，维护成本低。
- 你今天已有的 `mobile-responsive.css` 已经做了大量工作，只需要在此基础上做一个"壳专用增强版"。

### 2.3 备选方案：壳内嵌原生底部导航 + WebView 内容区

在 `MainActivity` 上加一个原生底部导航（3 个 Tab：首页/资源/管理），每个 Tab 对应一个路径：

- 首页 → `/`
- 资源 → `/resources/`
- 管理 → `/management/`（未登录时 redirect 到 `/login/`）

**问题：**

- 网站内部已经有自己的导航和面包屑，再加一层原生导航会让用户 confusion（比如从资源页点进详情，按返回是 WebView 后退，但底部 Tab 还是"资源"）。
- 登录态、通知、搜索、筛选等状态需要同步到 Tab 高亮，容易不一致。
- 需要处理不同角色看到不同 Tab（游客不该看到"管理"）。

**结论：可以做，但收益有限，且会让信息架构变复杂。不推荐作为第一步。**

### 2.4 不推荐方案：完全原生 UI + API

把资源列表、上传、审批、后台总览全部用 Android 原生重写，后端提供 REST API。

**为什么不推荐：**

1. **工作量巨大**。当前网站有 90+ 路由，涉及文件上传分片、PDF 预览、审批流、通知、备份等复杂交互。一个人维护两套 UI 几乎不可持续。
2. **功能很难"不多不少"**。原生端通常会阉割功能，或者在某些场景不得不 fallback 到 WebView，最终变成"原生套壳"的混合怪胎。
3. **与当前项目目标冲突**。MatchShell 现在的定位是"全屏调试壳"，不是独立产品。投入原生 UI 会把注意力从网站本身移开。

### 2.5 风险与反例

| 风险 | 说明 |
|---|---|
| 两套设计稿难以同步 | 网站迭代一个按钮，APP UI 也要跟；时间一长必然错位。 |
| 原生 UI 解决不了 PDF 预览/Office 预览 | 这些重度依赖网站生成的分页图/转换管线，原生重写等于重新造轮子。 |
| 审核与分发成本 | 一旦做独立 APP 就要考虑签名、更新、上架；目前只是局域网调试壳。 |
| 用户反而更不习惯 | 如果网站和 APP 交互差异大，成员在浏览器和 APP 之间切换会有学习成本。 |

### 2.6 建议落地顺序

1. **现在做**：APP 加自定义 User-Agent，暴露 `window.MatchShell` 可用状态。
2. **短期做**：网站根据 UA 隐藏顶部 header、底部加 3~5 个关键入口、放大触摸目标。
3. **中期观察**：如果 APP 真的变成主要入口，再考虑把"资源首页"做成更独立的 SPA 页面（仍在 WebView 里）。
4. **长期才考虑**：完全原生 UI，且必须有专人维护或业务强需求驱动。

---

## 3. 与当前 Memory 中暂缓项的关系

- Deep Link / App Link：角色首页目前不需要；用网站重定向即可。
- 拆分 `MainActivity`：如果未来做原生底部导航，才需要拆分出导航控制器。
- 应用内更新：与独立 UI 一样，属于"产品化"之后才需要的功能。

---

## 4. 结论

- **角色首页**：让网站 `/` 按登录角色重定向，APP 不改代码。这是最简单、最可靠、最符合现有代码结构的方案。
- **移动端独立 UI**：技术上可行，但**不建议现在做完全原生 UI**。先用"自定义 UA + 网站 APP 模式 CSS"低成本地把壳内体验提升到接近原生；如果后续业务证明需要，再逐步增加原生组件。

下一步需要用户拍板：

1. 是否同意让网站 `/` 按角色重定向？（需要改上游 `home` 视图）
2. 是否同意先走"自定义 UA + 网站 APP 模式"改善移动端体验？
3. 如果需要，我可以先给 APP 加 UA 并写一个上游 CSS 改造草案。

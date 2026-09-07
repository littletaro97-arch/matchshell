# MatchShell 兼容性验证清单

换手机测试时按下面顺序跑一遍。所有项在 **debug APK** 上验证即可；release 包用于确认签名/网络安全策略是否正常。

## 准备

1. 确认新手机与电脑连在同一 Wi-Fi。
2. 电脑端启动网站联调：双击 `E:\火柴公益官网建设-全新架构\启动手机联调.bat`，记录窗口给出的 IPv4 + 端口（如 `http://192.168.149.20:8880/`）。
3. 拿到 APK：`E:\课外项目\matchshell\dist\matchshell-v0.1.0-debug.apk`。
4. 手机上开启「开发者选项」和「USB 调试」。
5. 安装 APK：
   -  brand 手机若弹指纹/安全确认，按屏幕提示点。**荣耀/华为机器远程装包需要人在现场**（2026-09-03 实测）。
   -  也可把 APK 发到微信/QQ/系统文件管理器，在手机上直接安装。

---

## 验证项

### 1. 默认打开正式站
- 首次启动，应直接加载 `https://hcgy2018.site/`。
- 检查：
  - [ ] 页面正常显示，没有顶部状态栏/底部导航栏白/黑条。
  - [ ] 内容延伸到刘海/挖孔/手势区域。

### 2. 切换到局域网调试地址
- 长按右上角「刷新」按钮。
- 检查：
  - [ ] 弹出「最近 5 条历史地址」列表。
  - [ ] 点击某条地址右侧「删除」可移除该记录。
  - [ ] 首次安装时列表可能只有默认站；选「手动输入…」，输入框应为空。
  - [ ] 输入 `http://<电脑IP>:8880/`，保存后 Toast 提示已切换。
  - [ ] 页面加载本地站点，没有 `net::ERR_CLEARTEXT_NOT_PERMITTED`。

### 3. 本地调试时生产链接重定向
- 在本地站点内，点击指向 `https://hcgy2018.site/...` 的链接（如资源页）。
- 检查：
  - [ ] 没有跳系统浏览器。
  - [ ] 地址栏（虽然壳里没有地址栏）实际加载的是本地调试地址，可通过页面内容或 `chrome://inspect` 确认。

### 4. 返回键
- 在网站内进入子页面，按返回。
- 检查：
  - [ ] 先网页内后退；退到首页后再按返回才退出 APP。
- 如果网站注册了 `MatchShell.setBackHandler`：
  - [ ] 返回行为应符合网站注册的函数返回值。

### 5. 加载超时 / 错误页 / 自动重试
- 故意输入一个不通的地址，如 `http://192.168.1.254:8880/`。
- 检查：
  - [ ] 15 秒左右后出现错误页，提示「加载超时」或「无法连接到服务器」。
  - [ ] 错误页显示具体失败的 URL。
  - [ ] 关闭再打开手机 Wi-Fi（或从飞行模式切回），看到「网络已恢复，正在自动重试（x/3）」。
  - [ ] 如果服务器仍不通，自动重试最多 3 次后停止。
  - [ ] 点「重试」按钮能再次加载。

### 6. 文件上传
- 进入网站带 `<input type="file" multiple>` 的页面（如资源上传、编辑器）。
- 检查：
  - [ ] 点击上传按钮能弹出系统文件选择器。
  - [ ] 支持多选。

### 7. 下载
- 触发一个需要登录态的文件下载（中文文件名最佳）。
- 检查：
  - [ ] Android 13+ 首次会弹通知权限申请；允许/拒绝都应给出 Toast 提示。
  - [ ] 下载完成后能在系统下载/通知栏看到文件。
  - [ ] 中文文件名不乱码。

### 8. 全屏/旋转
- 旋转屏幕。
- 检查：
  - [ ] 页面不重建，滚动位置/表单内容不丢。
  - [ ] 横屏下仍然全屏，无系统栏遮挡。

### 9. JS 桥接（可选，需要网站配合）
- 在页面控制台或页面脚本里执行：
  ```javascript
  window.MatchShell.setBackHandler('myBack');
  window.myBack = function() { console.log('back consumed'); return true; };
  ```
- 检查：
  - [ ] 按返回键时 APP 不退出（因为 `myBack` 返回 true）。
  - [ ] `window.MatchShell.reload()` 能刷新页面。

### 10. release 包签名检查（可选）
```bash
"C:/Users/LittleTaro/AppData/Local/Android/Sdk/build-tools/37.0.0/apksigner.bat" verify -v "E:/课外项目/matchshell/dist/matchshell-v0.1.0-release.apk"
```
- 检查：
  - [ ] 输出显示 `Verified using v2 scheme` 等成功信息。

---

## 常见问题速查

| 现象 | 可能原因 |
|---|---|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 旧包签名或包名不同，先卸载旧 APP |
| `net::ERR_CLEARTEXT_NOT_PERMITTED` | 装成了 release 包；换 debug 包 |
| 页面比例被压扁 | `loadWithOverviewMode=false` 生效中；检查网站 viewport |
| 点击没反应 | 看 `adb logcat -s matchshell-touch:*` 是否有 `WV` 日志 |
| 安装后闪退 | 抓取 `adb logcat -d | grep AndroidRuntime` |

---

## 记录结果

测试完成后把下面信息记到本文件底部或告诉模型：

- 设备型号 / Android 版本 / 系统 WebView 版本
- 哪些项通过 / 哪些项失败
- 失败时的具体现象和 logcat 片段

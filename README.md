# Miku（飘雪）

一个把雪花盖在 Android 桌面上的小工具。屏幕上飘起你写的字、你选的图，想停就点一下。

作者：**du|壹玖**

## 想法从哪来

雪花落下来的时候很好看。我就想，为什么不能做一个小工具，让屏幕上也飘一会儿自己的雪？于是有了 Miku。后来看到一个能把画面盖在屏幕上、又不进截图的脚本，这个想法就更具体了：飘雪要能盖住整个屏幕，还要能在截图和录屏里干干净净地不出现。

## 功能

- **文字飘雪**：每个字单独飘，可旋转、翻转、倾斜
- **图片飘雪**：最多 8 张，自动缩放，可以只飘图片
- **数量 / 速度 / 大小**：三个滑杆，拖动即时生效
- **主题颜色**：跟随系统、始终浅色、始终深色、莫奈取色
- **授权通道**：Shizuku、Root、ADB 任选一条拿到悬浮窗权限
- **无法截屏、录屏获取**：给雪花单独一层打「跳过截图」标记，屏幕上照常显示，截图和录屏里没有雪花。这一项需要 root 或 ADB 授权后才能开启，只有系统悬浮窗权限时显示为灰色。
- **检查更新**：读本仓库 Release 里安装包的版本后缀，比当前版本大就提示更新，点一下直达下载

## 界面

`Miku` 主页面 + `关于` 页（从右往左滑入）。大卡片形态的状态卡、主题多选、滑杆、图片管理，全部用 Miuix 的 Compose 组件绘制。

## 构建

要求：JDK 17、Android SDK（compileSdk 37）、Gradle 9.x、AGP 9.x。

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew :app:assembleRelease
```

依赖里有 `app/libs/shizuku.jar`，需要自己从 Maven 取：

```
dev.rikka.shizuku:api:13.1.5
dev.rikka.shizuku:provider:13.1.5
```

`app/src/main/assets/` 里需要放从 Shizuku 管理器导出的 `rish` 与 `rish_shizuku.dex`（在 Shizuku 里「在终端应用中使用 Shizuku」→ 导出文件）。这两个文件属于 Shizuku 项目，本仓库不附带。

## 权限说明

- 悬浮窗权限：`SYSTEM_ALERT_WINDOW`
- Shizuku：`moe.shizuku.manager.permission.API_V23`
- 「无法截屏、录屏获取」走 SurfaceFlinger 的跳过截图标记，需要 shell/root 身份

## 声明

本项目仅供个人学习与自用，请勿用于任何侵犯他人隐私的用途。截图/录屏绕过功能依赖设备授权状态，作者不对使用场景负责。

## 许可

Apache License 2.0

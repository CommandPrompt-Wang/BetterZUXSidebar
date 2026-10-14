<div align="center">

<h1>BetterZUXSidebar</h1>
<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="BetterZUXSidebar">

<p></p>
<p>简体中文</p>

[![Android](https://img.shields.io/badge/API-35%2B-green)](https://developer.android.com/about/versions/15) [![Xposed](https://img.shields.io/badge/Xposed-LSPosed-blue)](https://github.com/LSPosed/LSPosed) [![Java](https://img.shields.io/badge/Java-11-orange)](https://openjdk.org/) [![License](https://img.shields.io/badge/License-GPL--3.0-orange)](LICENSE)

<p>ZUXOS 浮窗侧边栏增强模块</p>

</div>

**声明**：本仓库部分代码由 AIGC 辅助生成，可能存在不准确、不完整甚至有缺陷的地方，欢迎提交 Issue 或 Pull Request。


这是一个优化 ZUXOS 浮窗侧边栏的 LSPosed 模块，在尽量保留外观的情况下引入主流操作系统的特性，如搜索、按拼音分组、固定应用、拖放分屏等。

<p><sub>应用图标基于搜狗输入法自带图标二次创作；流萤像素画来源未知，如有侵权请联系删除</sub></p>

---

## 模块安装

0. **前置条件**：
   - Android 15 / API 35 或更高版本
   - 联想 ZUXOS 设备
     - 不保证在 ZUI 上的可用性
   - 已安装并启用兼容 LibXposed API 101 的 LSPosed 或其他 Xposed 框架
1. 在 [Releases](https://github.com/CommandPrompt-Wang/BetterZUXSidebar/releases) 下载 APK 并安装
2. 在 LSPosed Manager 中启用模块，确保下面两个模块被勾选：
   - `com.zui.freeform.sidebar`
   - `com.android.systemui`
3. 强制停止侧边栏进程和系统用户界面，使 Hook 重新加载。下面是使用 bash 命令的方法：

```bash
su -c "am force-stop com.zui.freeform.sidebar"
su -c "pidof com.android.systemui" | tr -dc '0-9\n' | awk 'NF==1 {print $1}' | while read -r pid; do su -c "kill -TERM $pid"; done
```

4. 待侧边栏和 SystemUI 重启，然后重新打开侧边栏。配置入口位于全部应用页的菜单中。

> 本模块强依赖 ZUXOS 私有实现，不是通用 Android 侧边栏模块。ZUXOS 升级、类名或方法名变化后，部分功能可能失效或降级。
> 
> 届时欢迎 [提交 Issue](https://github.com/CommandPrompt-Wang/BetterZUXSidebar/issues) 以帮助改进。

## 食用方法

模块没有独立的入口页面，所有配置都在注入后的侧边栏编辑：

<div style="display:flex; gap:8px;">
  <img src="assets/screenshot_p1.jpg" style="height:400px; width:auto;">
  <img src="assets/screenshot.png" style="height:400px; width:auto;">
</div>

模块对第二页（所有程序）的界面改动较大，而基本保持了第一页（快捷栏）的功能。

1. 顶部引入了**搜索栏**，当窗口打开时可以直接使用键盘搜索和导航
2. 加入了**固定应用**的功能（分割线上方），固定的应用可以在第一页（左侧）看到
  - 如果要编辑固定应用列表，请点击铅笔按钮进入编辑模式，此时从拖动即可加入/重排列表
  - 如果要恢复原有的“最常用的5个应用”功能，请在三点菜单中打开“快捷栏恢复最常用5应用”。第二页的固定应用列表不受影响。
3. 所有程序列表引入了**字母分组和定位条**，方便快速翻找应用
4. 加入了**快速分屏**的功能，非编辑模式下，长按拖动图标即可像拖动小窗那样创建分屏
5. 补全了**主题图标**功能。现在侧边栏的图标也会跟随主题色了
6. 可以**直接打开第二页**，当启用开关“直接打开此页面”时，可以直接打开所有程序界面，而不展示第一页快捷栏

## 开发构建

```bash
git clone https://github.com/CommandPrompt-Wang/BetterZUXSidebar.git
cd BetterZUXSidebar
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/BetterZUXSidebar-v<versionName>.apk
```

需要 JDK 11 + Android SDK 36.1。项目当前配置为 `minSdk 35`、`targetSdk 36`。

如需签名构建，请自备 `keystore.properties`；该文件包含签名信息，**请注意不要将其提交到版本库**。

## 日志

标签是 `BetterZUXSidebar`：

```bash
adb shell logcat -s BetterZUXSidebar
```

## 注意事项

- **强依赖 ZUI 私有实现**：大量类名、方法名、字段名和资源名通过反射访问，系统升级后可能失效。
  - **图标依赖 ZUI Launcher**：缺少 `com.zui.launcher` 或相关 Launcher3 类时会降级，图标外观和主题效果可能不同。
  - **分屏拖拽依赖 SystemUI**：窗口外拖拽能否进入分屏或自由窗，取决于当前 ZUI SystemUI 的原生实现。
- **数量上限**：当前连续键存储/读取循环上限为 200 项。
- **反射失败会局部降级**：应用列表、主题图标、厂商启动逻辑和主题刷新可能分别失败，不一定导致整个侧边栏不可用。

## 项目结构

```
app/src/main/java/moe/lovefirefly/betterzuxsidebar/
├── Hook/
│   └── MainHook.java            # Xposed 入口、目标包过滤与侧边栏页面 Hook
├── Ui/
│   ├── SidebarPanel.java        # 侧边栏主界面、搜索、网格、拖拽与动画
│   └── LauncherIconLoader.java  # ZUI Launcher3 图标渲染、主题图标与缓存
└── Utils/
	└── LogHelper.java           # 统一日志与日志级别控制
```

## TODO

[ ] 国际化

[ ] 跨版本兼容性检查

## 许可证

GPL-3.0 © 2025–2026 [CommandPrompt-Wang](https://github.com/CommandPrompt-Wang)

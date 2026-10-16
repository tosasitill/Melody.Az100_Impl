# 2026-10-14：恢复现代模块迁移并修复已连接但无音频

本轮恢复了被撤掉的现代模块迁移，将 hook 注册接到 libxposed API 102，并修复 LE Audio 连接初始化时误判交接及厂商 HFP 初始化清空输出的问题。最终安装版本为 **2.0.8 / versionCode 42**。用户实测确认一切正常。

## 环境与回滚定位

- 手机：PLJ110，Android 17 / SDK 37，`PLJ110_17.0.0.101(CN01)`。
- 框架：LSPosed 2.2.1 (7912)，运行时 API 102。
- 作用域：`com.android.bluetooth`、`com.oplus.melody`。
- 2026-10-14 16:20–16:21 的回滚撤掉了未提交的现代模块迁移，并将安装包从 2.0.5 恢复为 2.0.3 / 39；Git 历史中没有对应的 revert commit。
- 2.0.4 引入现代入口后，框架改为加载该入口，但最初入口为空；后续只转发到 YukiHookAPI 1.3.2，仍未安装实际的现代 hook。
- 16:26 重启后又出现作用域丢失，16:36 已恢复两条作用域。这与入口未安装 hook 是两项独立问题。

原安装包、初始工作区 patch、LSPosed 配置和历史修改保存在本地 `.git/hook-repair-20261014/`，不随代码提交。

## 最终实现

`ModernXposedEntry.onModuleLoaded()` 初始化日志及模块 APK 路径，`onPackageReady()` 按作用域安装 hook。通过 `Application.attach` 捕获目标 Application，并在 Melody provider 创建前完成 hook 安装。

KavaRef 保留成员选择，`HookRuntime` 通过 `framework.hook(member).intercept` 完成实际注册。`HookCall` 与 `HookCallbacks` 保留参数修改、提前返回、after 回调、异常恢复和每次调用的独立状态。

移除旧 `Az100Entry`、`assets/xposed_init`、YukiHookAPI/KSP 和 legacy Xposed 依赖。现代入口、静态作用域及 API 声明位于 `META-INF/xposed/`。设置使用模块应用的私有配置和 LSPosed remote preferences；两处设置入口同步提交，蓝牙 hook 不依赖模块应用常驻。

回滚前关于手机重新接管的修改也已恢复：临时 API 释放仅短暂抑制自动补连，明确的手机连接可解除交接状态；已确认远端所有权仍等待 native MEDIA 返回或用户明确选择。

## 无音频的两条已确认路径

第一条发生在 17:11:05：LE Audio 没有 active group 时，ROM 调用清理接口，旧逻辑仅因存在已连接 AZ100 就开启交接窗口。17:11:07 激活后，双模同步的输出撤销因此被放行，group 又变为 inactive。

修复后，API 释放必须针对确实活跃的 AZ100 group；`setActiveGroupWithDevice(null)` 的 after 逻辑还需确认本次调用前有有效的 AZ100 active group。本来就是 inactive 的清理不会开启交接窗口。

第二条在 2.0.7 / 41 的实机复验中暴露：17:29:57 LE Audio 激活成功，17:29:58 厂商 HFP 初始化直接调用 `LeAudioService.setActiveDevice(null)`，把输出清空。只修正第一条不足以稳定保持输出。

2.0.8 对这种内部 null 选择增加短期保护，要求刚激活、当前 group 中仍有已连接 AZ100，且没有交接状态。当前 ROM 的 Binder 对用户选择 null 输出调用 `removeActiveDevice(true)`，该路径继续放行；确认远端接管后短期保护也不生效。

## 验证结果

- JDK 21 构建 debug 和未签名 release APK 成功。
- 40 项 JVM 测试全部通过：RACE 13、hook 回调 8、连接门控 3、交接状态 16。
- 新蓝牙进程加载 2.0.8 / 42，启动时注册 49 个 hook，厂商扩展加载后继续安装对应 hook。
- native patch `rc=0`，`OplusBassClientService` 实例注入成功。
- 17:35:21 的初始化清理记录 `yieldOnRelease skipped group=-1`，没有制造交接窗口。
- 17:35:27 AZ100 激活为 group 1；随后记录 `dualModeSyncSkipped`、`leaReleaseHeld` 和 `classicStartupReleaseHeld`。
- 后续状态中 `currentlyActiveGroupId: 1`、`mActiveAudioOutDevice` 和 `mLeAudioActiveDevice` 均指向 AZ100，classic active 为空。
- 独立 AudioTrack 探针不指定 preferred device，使用 MEDIA / 48 kHz / 双声道 PCM。两次路由查询均为 `TYPE_BLE_HEADSET (26)` / `Technics EAH-AZ100`；播放帧计数从 90,112 增至 202,752，最终为 225,280。
- 用户随后实测确认“一切正常”。本轮没有另行重跑完整的电脑接管序列；之前的交接实测见 `2026-10-14_lea-host-handoff-fix.md`。
- remote preferences 在不重启蓝牙进程的情况下将 `diagnose=false` 更新为 `true`，目标进程的 `prefs` 命令确认读取到新值。随后无线 ADB 断线，恢复为 false 的指令未送达；其他三个开关保持 true。详细日志可从模块设置关闭。

## 复验步骤

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home
gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease -Dorg.gradle.java.home="$JAVA_HOME"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.tosasitill.az100/.MainActivity
adb shell su -c 'svc bluetooth disable'
adb shell su -c 'kill -9 $(pidof com.android.bluetooth)'
adb shell su -c 'svc bluetooth enable'
adb shell am force-stop com.oplus.melody
adb shell su -c 'cat /data/misc/bluetooth/az100.log'
adb shell su -c 'dumpsys activity service com.android.bluetooth/.btservice.AdapterService --print'
```

连接耳机并播放媒体，确认 AZ100 既是已连接设备，也是有效 LE Audio active 输出，且 HFP 初始化后不会丢失。再通过系统媒体输出选择器切换到手机扬声器，确认显式输出选择可用。

检查或恢复诊断设置：

```bash
adb shell su -c 'content call --uri content://com.tosasitill.az100.config --method set --extra key:s:diagnose --extra value:b:false'
adb shell su -c 'setprop debug.az100.action prefs'
adb shell su -c 'tail -n 8 /data/misc/bluetooth/az100.log'
```

本地关键证据位于 `.git/hook-repair-20261014/`：`no-audio-live-adapter.txt`、`audio-fix-first-adapter.txt`、`final-bluetooth-first.txt`、`final-adapter-first.txt`、`final-adapter-after-playback.txt`、`audio-probe/AudioRouteProbe.java`、`audio-probe/result-first.txt` 及构建日志。探针是独立验证文件，没有加入模块 APK。原始日志含设备地址，仅保留在本地证据目录。

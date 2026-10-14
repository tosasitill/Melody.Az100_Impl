# 2026-10-14：手动断开与电脑交接修复

针对用户报告的两项问题：手机手动断开后自动连回，以及手机连接时电脑无法连接 / 接管。修复构建版本为 **2.0.9 / 43**。本轮设备操作限于读取现有日志、系统状态和备份已安装 APK，以及交付模块更新；未运行临时 DEX、辅助程序、测试音或远程连接测试。

## 已确认的运行时链路

设备当前安装基线为 2.0.8 / 42；Bluetooth APK SHA-256 为 `ad1899cbdf7969d2a279cf447052bd2ea146cb3f29ebcba7f62ade55b9e05813`，与此前定向分析的 APK 一致。

21:04:53.068–53.240，LE Audio 两只耳塞、A2DP 和 HFP 依次从 Connected 变成 Disconnecting / Disconnected。LE ACL 与 classic ACL 均记录 `CONNECTION_TERMINATED_BY_LOCAL_HOST(0x16)`，符合本机主动断开。

21:04:54.144，手机再次发起 classic 连接；21:04:54.406–54.410，两只耳塞的 LE Audio 同时进入 Connecting。21:04:58.934 另一只耳塞 Connected，21:04:59.377 又成为 LE Audio active 输出。模块日志中的 VCS / LEA 连接与该时间线对应。这证明第一个问题确实发生，不能用最终的 Connected 状态解释成用户重新连接。

`AdapterServiceBinder.disconnectAllEnabledProfiles()` 经过用户、连接权限和设备关联检查后调用 `AdapterService.disconnectAllEnabledProfiles(device, 1111)`。厂商内部清理调用同一个服务方法时使用 reason 0。以 reason 1111 记录用户断开，避免把内部清理误判为手动断开。

## 连接来源判断的缺口

2.0.8 的 hook 对每次 `LeAudioService.connect()` 无条件调用 `resumeLeaLocally()`，依据是“自动重连不会调用 connect”。当前实际运行的系统与厂商代码否定了这个前提：

- `PhonePolicy.processConnectOtherProfiles()` 调用 `LeAudioService.connect()` 补齐 profile。
- `OplusLeAudioPhonePolicyExt.connectLeAudio()` 先连接 VCP / CSIP / BAS，再调用 LE Audio connect。
- 该厂商方法又被 group 补连和 classic ACL 连接回调调用。
- 模块的双耳补连也调用相同的服务方法。

因此，后台补连可无条件清除远端交接、清空用于检测 MEDIA 撤回的旧上下文，并重新允许 group 激活。第一项故障的重连已由运行时日志确认；第二项故障目前没有保存到一次成功到达远端所有权信号的完整电脑尝试，不能声称已经实机证明根因或修复成功。

另一个缺口是临时让出窗口：旧 hook 仅在 `isRemoteYielding()` 时真正拦截非空 `setActiveDevice()`，只处于临时让出时仍允许原方法调用 `groupSetAudioResume`。补连抑制也只持续 8 秒，而让出窗口为 30 秒。

## 修复行为

- 记录整副耳机的手动断开状态，在用户明确重新连接 / 选择前，禁止自动连接和重新激活。native MEDIA 报告或时间流逝不会解除手动断开。
- 明确区分经过权限检查的 Binder 用户连接与后台 `connect()`。后台连接保持上下文历史和交接状态；用户选择恢复两只耳塞的连接许可及补连状态。
- 在厂商 `connectLeAudio()` 的 fan-out 之前阻止补连，避免只拦 LE Audio connect，却已经通过 VCP / CSIP / BAS 重新打开 ACL。
- 在执行时拦截排队的 `PhonePolicy` 补连，以及 AdapterService、A2DP outgoing 和 HFP outgoing 的后台连接。手动断开期间同时拒绝 AZ100 profile 的连接接纳和激活。
- 在临时让出和已确认远端交接期间，都阻止非空 LE Audio `setActiveDevice()` / `setActiveGroupWithDevice()` 重新激活 group。
- 后台补连抑制覆盖完整的 30 秒临时让出窗口；已确认远端状态和手动断开不依赖该超时。用户明确选择始终可提前解除。
- Binder 的调用标记使用每次 hook 调用状态保存并恢复，嵌套调用或异常不会清掉外层标记。
- 双耳补连的断开标记只由用户选择解除；开关状态在补连执行时再次检查。

所有状态限于当前蓝牙进程，未写入 FORBIDDEN 策略或持久化禁止连接。重启蓝牙进程后重新遵循正常连接策略。

## 离线验证

JDK 21 构建 debug / release 并运行 48 项 JVM 测试通过：RACE 13、hook 回调 9、连接门控 3、交接 / 断开状态 23。

新增测试回放手动断开后 1.3 秒的后台连接、重复 MEDIA 报告、90 秒后补连、用户重新选择、远端 `0x14` 后多次后台 connect、后台 connect 保留上下文以检测 MEDIA 撤回、临时释放后的自动激活抑制，以及嵌套标记与异常清理。

## 实机交付与使用反馈

本轮没有主动触发断开、连接、播放、进程重启或手机重启。21:37:37 已覆盖安装 2.0.9 / 43，package manager 确认版本；随后读取的蓝牙进程日志仍为 2.0.8 / 42，因此新 hook 尚未加载。后续只读检查证实，正常关闭 / 打开蓝牙不保证进程退出；应以目标进程入口日志的版本确认模块重新加载。安装成功仅表示模块包交付成功，不能据此声称两项故障已经实机修复。

此前用户确认手动断开有效，但电脑仍没有音频。对应快照显示手机 native group 已为 IDLE、CIG=NONE、CIS=0；电脑 AVDTP 媒体通道尚未见打开完成，历史证据见 [Mac 媒体通道排查](2026-10-14_mac-audio-publish-stall.md)。

随后只读核对确认，Bluetooth 已在 **22:14:58.574** 实际加载 `module 2.0.9/43 loaded via libxposed API 102 in com.android.bluetooth`，安装包也为 `versionName=2.0.9`、`versionCode=43`。用户表示若当前运行 2.0.9 则已无问题，并要求直接推送仓库；运行版本条件已满足。验证设备为 PLJ110 / Android 17 / `PLJ110_17.0.0.101(CN01)`，LSPosed 2.2.1 (7912) / API 102。此次确认没有主动连接、播放或重启服务。

### 后续回归步骤

以下步骤留作需要再次验证时的正常操作参考，本次交付不再要求用户重复：

1. 手机正常连接 AZ100 并通过常用播放器确认声音。
2. 在手机蓝牙设置中点“断开”，观察至少 35 秒，应保持断开；随后从电脑正常连接耳机。
3. 手机明确重新连接 AZ100，再在电脑正常连接 / 切换音频，确认电脑连接与音频能够建立并保持。
4. 回到手机明确选择 AZ100，确认双耳连接和正常音频恢复。

只读日志读取：

```bash
adb shell su -c 'cat /data/misc/bluetooth/az100.log'
adb shell su -c 'dumpsys activity service com.android.bluetooth/.btservice.AdapterService --print'
```

应关注 `manual disconnect, automatic connections suppressed for both earbuds`、`leaConnectSuppressed`、`vendorLeaConnectSuppressed`、`profileRetrySuppressed`、`groupActivationYielded`，以及用户重选后的 `handoff/disconnect ended by local selection`。这些日志需与实际操作、两端连接状态和用户听音一致。

如果电脑仍无法建立连接，需要保存该次正常操作后的日志，确认是否出现 MEDIA 撤回、ACL `0x14` 或其他资源事件；不能仅凭代码修复推断电脑已接管。

本地证据保存于 `.git/handoff-repair-20261014/`：`bluetooth-before.txt`、`adapter-before.txt`、`audio-before.txt`、`installed-208.apk`、定向 ASC 输出及构建日志。原始证据不提交；本报告未包含设备地址。

交付 APK：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `4e5d53c88739e8a324809098d102b6046ab8cdf52ef5bc0e67b4e128564f2ca9`。未签名 release 为 `app/build/outputs/apk/release/app-release-unsigned.apk`，SHA-256 `8dee3323fa3e841bac242a138c3f868f0fddd0770d7fe8a4065a0b56c88ab518`。

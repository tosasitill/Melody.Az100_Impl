# Technics EAH-AZ100 适配

LSPosed 模块，当前版本 **2.0.9**（versionCode 43）。它做两件分开的事：

- 在 `com.oplus.melody` 里应答音量面板，并用自带的 Airoha SPP/RACE 读写降噪和电量。
- 在 `com.android.bluetooth` 里让 AZ100 走 LE Audio（LC3），并让系统设置出现 LE Audio 开关。

SystemUI、设备空间和 `com.panasonic.technicsaudioconnect` 都不注入。Audio Connect 可以卸载。

## 构建

仓库没有 Gradle Wrapper。本机用 JDK 21：

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home
gradle :app:assembleDebug -Dorg.gradle.java.home="$JAVA_HOME"
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。运行 `gradle :app:testDebugUnitTest :app:assembleRelease` 可执行 JVM 回归测试并生成未签名 release APK。

JDK 25/26 会在 `JdkImageTransform` 失败。

## LSPosed

模块使用 libxposed 现代入口 `ModernXposedEntry`，通过 `META-INF/xposed/java_init.list` 注册；KavaRef 负责查找成员，libxposed API 102 负责安装 hook。APK 不再包含 legacy `xposed_init` 或 YukiHookAPI 入口。已在 LSPosed **2.2.1 (7912) / API 102** 验证，声明最低 API 101，但尚未实测 API 101。

1. 安装并启用模块。
2. 作用域勾 `com.oplus.melody` 和 `com.android.bluetooth`。
   不要勾 `com.heytap.mydevices` 或 `com.android.systemui`。
3. 只改 Melody 行为时：`adb shell am force-stop com.oplus.melody`。
4. 改了蓝牙钩子后，重启蓝牙进程才会加载新代码：

```bash
adb shell su -c 'svc bluetooth disable'
adb shell su -c 'kill -9 $(pidof com.android.bluetooth)'
adb shell su -c 'svc bluetooth enable'
```

日志：

```bash
adb logcat -s AZ100:V
adb shell su -c 'cat /data/misc/bluetooth/az100.log'
```

启动版本、hook 数量和异常始终记录到 logcat 与 libxposed logger；详细 trace 只有打开“详细日志”后才写入 logcat 和文件。蓝牙进程的诊断文件为 `/data/misc/bluetooth/az100.log`；Melody 文件日志在 `/sdcard/Android/data/com.oplus.melody/files/az100.log`。稳定版默认关闭 trace 和 adb 实验控制台。

## 音量面板和电量

长按磁贴进入 Melody 的 AZ100 详情页。模块保留原生活动和工具栏，跳过 Melody 不认识 AZ100 的产品加载，显示连接状态、左右耳和充电盒电量，并提供“立即刷新电量”。刷新先读主耳方向，再等电量 ACK 和异步通知，收到后关闭 SPP。

面板模式：`5` 降噪、`1` 关闭、`2` 通透。自适应不在轮换里；耳机如果正处在自适应，显示为降噪，下一次切换先清掉。

耳机连上后最多读一次当前模式和电量。之后的 query 只回内存，不会因为 SystemUI 的媒体路由回调反复开 SPP。

## 设置

模块有四个开关，两个入口读同一份配置：

- LSPosed 模块列表里的 **AZ100** 应用（`MainActivity`）
- 上面那个详情页里的「模块设置」区

| 开关 | 默认 | 作用 |
| --- | --- | --- |
| 自动切换到 LE Audio | 开 | 关掉后 `connectLeaNow()` 直接返回，不再把重连升级成 LEA |
| 自动连接另一只耳塞 | 开 | 一只连上后把同组另一只也连上 |
| 音量修复 | 开 | 抬高低音量下限，并丢弃重复的绝对音量写入 |
| 详细日志 | 关 | 开启 UUID、音量、RACE 和交接 trace；默认关闭，不影响行为 hook |

「自动切换到 LE Audio」是下面那个问题的出口：用系统详情页的 LE Audio 开关关掉 LEA 后，ROM 会把音频回退到 LDAC，而 `onRemoteUuidsUpdated` 随后触发 `hookDirectLeaConnect`，把设备又升级回 LEA。关掉这个开关即可停止该行为，系统自带的 LE Audio 开关仍然有效。

模块应用使用 `MODE_PRIVATE` 保存本地配置，并通过 `XposedServiceHelper` 把四个开关发布到 LSPosed remote preferences。蓝牙和 Melody hook 直接读取框架提供的 remote preferences，不依赖模块应用常驻，也不需要跨 UID 读取配置文件。

模块设置页和 Melody 的设置入口都调用 `Prefs.save()`；本地与框架设置同步提交成功后才显示保存成功。`Az100ConfigProvider.call()` 保留为 Melody 的设置接口。

修改会同步到已运行的目标进程；连接类开关在下次连接时应用，不需要重启蓝牙或手机。稳定版关闭 adb 实验控制台；请使用设置页检查和修改配置。

## LE Audio

AZ100 有两只耳的地址，都要按同一副耳机处理：

| 地址 | 角色 |
| --- | --- |
| `B8:20:8E:EE:84:85` | 主耳，classic profile 在这边 |
| `B8:20:8E:EE:3C:62` | 另一只耳，LE Audio group 的另一名成员 |

当前实机状态（OnePlus PLJ110 / ColorOS，2026-10-12）：

- `0000184e` 进入 `mUuidsLe`，`le_audio: ALLOWED`
- LE Audio 连接后 ASE 走到 LC3
- `184E` 一到就调用官方 `connectLeAudio()`，不再先连 A2DP/LDAC 再被踢掉
- 模块日志有 `dual-coexist: ... marked LEA-coexistent (directLeaConnect)`

### 为什么官方路径不会自己连

OPPO 自家耳机的直连入口是 `OplusDualModeManagerImpl.onRemoteUuidsUpdated()`。它只处理已经放进 `mPendingLeaConnectAfterDiscover` 的设备，而这个集合只在双连接偏好变化时填充。AZ100 不回答 OPPO 的 Echo/SmartAudio 握手，`/data/user_de/0/com.android.bluetooth/shared_prefs/dual_mode_support.xml` 是空的，所以官方 `connectLeAudio()` 在一次普通连接里不会被调用。

`OplusDualModeCoexistHook` 做四件事：

1. `isSupportDualConnection()` 和 `isDualConnectionNeeded()` 对 AZ100 回答 true。
2. `184E` 到达后，按地址去重，主动调用 `OplusLeAudioPhonePolicyExt.connectLeAudio()`。因为第 1 条，它走到 `setConnectionPolicy(LE_AUDIO, 100)`，不是 `connect br profile first`。
3. 把已经被写成 FORBIDDEN 的 A2DP/HFP 策略恢复成 ALLOWED，避免 LE Audio 没接上时完全没声音。
4. LE Audio 连上后接管 active device，并挡住 classic profile 立刻抢回去。

同一地址在上一次直连任务还没执行时不会重复排队。

### 手动断开与重新连接

手机通过系统入口手动断开 AZ100 后，模块记录整副耳机的断开意图，阻止自动 LE Audio 升级、双耳补连、厂商 profile 补连和 classic fallback。MEDIA 状态报告或后台调用 `LeAudioService.connect()` 都不能解除这个状态。重新在手机上明确连接 / 选择 AZ100 时，两只耳塞一起恢复连接许可。该状态保存在当前蓝牙进程内，重启蓝牙进程后重新遵循正常连接策略。

`LeAudioService.connect()` 同时被用户入口、`PhonePolicy` 和厂商连接策略调用；仅经过权限检查的 Binder 用户入口可以重新接管耳机。2.0.9 修复了将后台连接误判成用户选择的逻辑，并在确认交接和临时让出期间都拦截 group 激活，避免原方法调用 native `groupSetAudioResume` 把音频拉回手机。

### 手机 LE Audio 与电脑交接

电脑连接时，耳机仍可能保持手机的 LE ACL；这不代表手机仍拥有媒体流。模块监听 MEDIA 上下文撤回、blocked-media 建议、group 主动停用，以及耳机用 HCI `0x14` 释放 LE 资源。确认远端接管后，释放不受连接后的 15 秒保护限制。

连接初始化时，对本来就没有 active group 的清理不会开启交接窗口。ROM 在 HFP 初始化期间直接调用 `LeAudioService.setActiveDevice(null)` 时，只对刚激活且仍连接的 AZ100 group 保留短期保护，防止“已连接但没有音频输出”。用户通过 Binder 选择其他输出时的 `removeActiveDevice()` 继续放行。

确认交接后，模块停止自动升级、补连另一只耳塞和重新激活 group，也取消已排队的输出重设。只有 LE Audio 已在实际播放时，才断开 classic A2DP；退出独占状态只清掉本地标记，不主动重连手机 A2DP。这个状态持续到耳机重新提供 MEDIA 上下文，或用户在手机上明确连接 / 选择 AZ100；不会在 30 秒后自行抢回。

验证：先让手机通过 LE Audio 连接，再在电脑上连接并播放，等待至少 35 秒，确认声音留在电脑；随后在手机上选择耳机，确认 LE Audio 可以恢复。交接后不应有模块发起的 `A2DP reconnect requested` 或 `leaExclusiveOn`。

当前自动化验证覆盖 **48 项**测试，其中交接 / 断开状态 23 项、hook 回调 9 项。2.0.8 曾在 **PLJ110 / Android 17 / PLJ110_17.0.0.101(CN01)** 验证入口、native patch 和 LE Audio active 输出；随后用户报告临时音频探针导致黑屏及无法重启，相关实机测试方式已在 `AGENTS.md` 禁止。2.0.9 已通过离线测试及 debug/release 构建并覆盖安装。**22:14:58 的 Bluetooth 入口日志已确认实际加载 2.0.9 / 43、libxposed API 102；用户随后确认当前使用正常。** 安装和开关蓝牙均不保证目标进程退出，后续更新仍应以 `/data/misc/bluetooth/az100.log` 的入口版本确认新 hook 已加载。

此前仍运行 2.0.8 时，用户确认手动断开有效，但电脑没有音频。对应快照中手机 native group 为 IDLE、CIG=NONE、CIS=0；Mac 已完成 AAC 配置，但 AVDTP 的最后一条媒体状态为 OPENING，CoreAudio 没有发布耳机输出。那次连接中菜单栏工具的自动 RFCOMM 读取与媒体建立并行，尚未证明其因果关系；历史证据见 [Mac 媒体通道排查](2026-10-14_mac-audio-publish-stall.md)。

手动断开和电脑交接的修复与验证边界见 `2026-10-14_manual-disconnect-and-handoff-fix.md`。回滚与挂载排查见 `2026-10-14_modern-hook-and-audio-fix.md`；先前的电脑交接记录见 `2026-10-14_lea-host-handoff-fix.md`。

### 设置里的开关

无线设置详情页的开关来自 `com.android.settings.bluetooth.BluetoothDetailsProfilesController`。`LeAudioProfile` 进 `mProfiles` 有两条路：

- LE Audio 变成 `STATE_CONNECTED` 时，`CachedBluetoothDevice.onProfileStateChanged()` 无条件加入。
- `updateProfiles()` 看到 `getUuids()` 含 `0000184e`。

只补 UUID 不够。设置进程可能在连接之后才打开，那时 `getUuids()` 仍是 null；LE Audio 必须真正连上，详情页重进后开关才稳定出现。

`LeAudioWhitelistHook` 只影响 Melody 自己的 BR/LE 视图。`com.android.settings` 不查 Melody 白名单，这个钩子和系统开关无关。

### 厂商栈里的另外两道门

- `libbluetooth_jni.so` 的 `oplus_btif_is_lea_device()` 对非 OPPO 产品返回 false，随后 `oplus_handle_filter_uuid()` 剥掉 `184E/184F/1846/1844`。`NativeLeaDeviceHook` 在蓝牙进程内把该函数改成 `mov w0, #1; ret`。序言不匹配时拒绝改写。当前固件偏移是 `0x643180`，系统升级后要重新核对。
- `BassClientService.mOplusBassClientService` 在这版 ROM 上从未赋值。`184F` 放行后，`connect()` 会空指针并把整批 profile 连接打断。`BassClientFixHook` 补上实例；补不上时直接让这次 BASS 连接返回 false。

`SPLICE_LE_AUDIO_UUIDS` 仍是设置开关的兜底：它在 `setUuidsLe()` 入参里补 `0000184e`。这个注入不依赖「详细日志」。后者只控制日志：UUID 流水线、音量诊断，以及 `LeAudioTransportHook` 里的 `setDeviceVolume` 记录。音量修复本身（`allowVcp`、连接策略放行、音量上报捕获、成员生命周期）是无条件安装的，关掉「详细日志」不会让它失效。

## 代码

| 文件 | 进程 | 作用 |
| --- | --- | --- |
| `ModernXposedEntry` | 入口 | 加载现代模块、remote preferences 和目标进程 hook |
| `HookRuntime` / `HookCall` | 适配层 | KavaRef 成员选择、libxposed 注册及 before/after 回调 |
| `Az100Application` / `Prefs` | 模块应用 | 连接框架服务并发布设置 |
| `Az100Hook` | 配置 | 两条作用域、AZ100 设备识别和协议常量 |
| `MelodyProviderHook` | Melody | 应答音量面板 query 和点击 |
| `MelodyDetailBatteryHook` | Melody | 详情页手动刷新电量 |
| `DirectAirohaController` | Melody | 按次打开 SPP，发完即关 |
| `AirohaRace` | Melody | H4/RACE 分帧 |
| `LeAudioWhitelistHook` | Melody | Melody 内部 BR/LE 视图，不影响系统开关 |
| `LeAudioTransportHook` | 蓝牙 | LE 配对、UUID 注入、音量下限和双耳补连 |
| `NativeLeaDeviceHook` | 蓝牙 | 打开 native LE Audio 门，停止剥离 UUID |
| `BassClientFixHook` | 蓝牙 | 修补 BASS 空指针 |
| `OplusDualModeCoexistHook` | 蓝牙 | 直连 LE Audio，并阻止 classic 抢回 active |
| `LeaHandoffState` | 蓝牙 | 记录跨主机媒体交接，直到 native MEDIA 返回或手机明确选择 |

`NativeLeaWhitelistHook` 保留但未安装。它写的是地址键，native 门实际按厂商/产品 ID 查，两者对不上。

## 验证

```bash
adb shell su -c 'grep -E "directLeaConnect|native: patch rc|bass-npe" /data/misc/bluetooth/az100.log'
adb shell su -c 'dumpsys bluetooth_manager | grep -A3 "84:85"'
```

期望：

- `native: patch rc=0`
- `bass-npe: injected OplusBassClientService`
- 两个 AZ100 地址都有 `directLeaConnect`
- `[LE UUIDs]` 含 `0000184e`
- LE Audio 连接后 `[ACL ... LE:Y]`
- `AdapterService --print` 中 `currentlyActiveGroupId` 有效且 `mLeAudioActiveDevice` 为 AZ100；只看到连接数不够

```bash
adb shell su -c 'dumpsys activity service com.android.bluetooth/.btservice.AdapterService --print'
````

`persist.bluetooth.*` 属于 `bluetooth_prop`。`adb shell getprop` 可能返回空，读的时候用 `adb shell su -c "getprop ..."`。

## macOS

`macos/` 用同一套 RACE 帧在 Mac 上切换降噪、读电量和读写耳机固件的 LE Audio 开关。它改的是耳机，不改手机里的系统设置。

```bash
cd macos
./az100.sh
./az100.sh leaudio          # 读固件侧开关
./az100-menubar.sh
```

细节见 `macos/README.md`。协议字节见 `docs-audio-connect.md`。

`2026-10-11_*.md` 和 `2026-10-12_*.md` 是当天的排查记录，里面的“下一步”和“当前状态”以本文件为准。

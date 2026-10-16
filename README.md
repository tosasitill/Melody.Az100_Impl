# Technics EAH-AZ100 适配

LSPosed 模块，`app/build.gradle` 声明的源码版本为 **2.0.10**（versionCode 44）；最近有实机入口日志和用户反馈确认的版本是 **2.0.9 / 43**，两者不能混用。

**2026-10-16 文档核对：当前工作区使用 libxposed API 102 现代入口 `ModernXposedEntry`，没有 YukiHookAPI 依赖。** 早前版本的本文件曾描述一次未落地的 YukiHookAPI 迁移，那与打包产物不符，已按实际源码更正。模块做两件分开的事：

- 在 `com.oplus.melody` 里应答音量面板，并用自带的 Airoha SPP/RACE 读写降噪和电量。
- 在 `com.android.bluetooth` 里让 AZ100 走 LE Audio（LC3），并让系统设置出现 LE Audio 开关。

SystemUI、设备空间和 `com.panasonic.technicsaudioconnect` 都不注入。Audio Connect 可以卸载。

## 构建

仓库没有 Gradle Wrapper。本机用 JDK 21：

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home
gradle :app:assembleDebug -Dorg.gradle.java.home="$JAVA_HOME"
```

构建成功时输出 `app/build/outputs/apk/debug/app-debug.apk`。JVM 回归命令为 `gradle :app:testDebugUnitTest`；`gradle :app:assembleRelease` 用于生成未签名 release APK。JDK 25/26 曾在 `JdkImageTransform` 失败，仍使用 JDK 21。

### 构建状态

2026-10-16 使用 JDK 21 执行 `gradle :app:testDebugUnitTest :app:assembleDebug -Dorg.gradle.java.home="$JAVA_HOME"` 成功：**53 个 JVM 测试全部通过**，并生成 `app/build/outputs/apk/debug/app-debug.apk`。

- 当前配置是 AGP **9.1.0**、Kotlin 插件 **2.4.21**、`compileSdk 37`、`targetSdk 35`、`minSdk 29`；Java 编译目标为 17。
- 依赖只来自 `google()` 和 `mavenCentral()`，没有本地 Maven 仓库路径，也没有 KSP 或注解处理器。
- `META-INF/xposed/java_init.list`、`module.prop` 和 `scope.list` 是签入的静态资源，`java_init.list` 登记手写的 `com.tosasitill.az100.ModernXposedEntry`。
- 模块设置页通过 libxposed remote preferences 发布配置，hook 进程读同一份 remote preferences。

原生库由 `app/src/main/cpp/az100_patch.c` 用 NDK clang 编译（见 `AGENTS.md`），产物签入 `app/src/main/jniLibs/arm64-v8a/libaz100patch.so`。

### 原生库的 16 KB 页对齐

链接原生库必须带 `-Wl,-z,max-page-size=16384`。默认链接产出 `Align 0x1000` 的 `LOAD` 段，在 16 KB 页设备上会被拒绝加载；`NativeLeaDeviceHook` 正是通过 `System.load("<apk>!/lib/arm64-v8a/libaz100patch.so")` 从 APK 内直接映射，对段对齐敏感。校验：

```bash
llvm-readelf -lW app/src/main/jniLibs/arm64-v8a/libaz100patch.so | grep LOAD   # 每段须为 0x4000
zipalign -c -P 16 -v 4 app/build/outputs/apk/debug/app-debug.apk
```

这个标志只影响本模块 `.so` 的段布局：代码洞偏移 `0x115ba80` 指向的是目标库 `libbluetooth_jni.so`，且洞穴合法性用运行时 `sysconf(_SC_PAGESIZE)` 判定，补丁判定逻辑不受影响。

## LSPosed

已验证的 2.0.9 使用 libxposed 现代入口 `ModernXposedEntry`，KavaRef 查找成员，libxposed API 102 安装 hook。历史验证环境是 LSPosed **2.2.1 (7912) / API 102**。

`META-INF/xposed` 是签入的静态资源，`java_init.list` 指向手写的 **`com.tosasitill.az100.ModernXposedEntry`**，它直接实现 libxposed API 102 的 `XposedModule`，没有注解处理器或生成类参与。APK 中的入口类以打包产物为准，不要只看源码。

`module.prop` 由 KSP 生成，声明最低 API **101**、目标 API **102** 和静态作用域；这不等于 API 101 已实测，也不等于新入口已在 API 102 实机验证。

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
adb logcat -s Az100LSPosed:V AZ100:V
adb shell su -c 'cat /data/misc/bluetooth/az100.log'
```

当前 `Logs.i/e` 不受「详细日志」控制，调用 Android logcat（tag `Az100LSPosed`）和框架 logger。`Logs.trace` 使用 tag `AZ100`，只有 `Prefs.diagnose` 为 true 时才写入 logcat 和文件；`Logs.DEBUG=false` 时 `Logs.d` 不输出。新入口记录 context attached 的版本和 registered hooks 事件，**不再输出 hook 数量**，不要继续只按旧入口的日志格式判断。

蓝牙诊断文件为 `/data/misc/bluetooth/az100.log`；Melody 在安装时拿到非空 Context 才会设置 `/sdcard/Android/data/com.oplus.melody/files/az100.log`。文件超过 2 MiB 时轮转为 `.1`。默认关闭详细日志时，入口版本也不会写入这个 trace 文件；文件可能缺失或留有旧记录，应先看本次进程的 logcat / 框架日志。当前源码关闭详细日志和 adb 实验控制台。

## 音量面板和电量

长按磁贴进入 Melody 的 AZ100 详情页。模块保留原生活动和工具栏，跳过 Melody 不认识 AZ100 的产品加载，显示连接状态、左右耳和充电盒电量，并提供“立即刷新电量”。刷新先读主耳方向，再依次请求两只耳的电量：每次先等 ACK，随后最多等 6000 ms 的异步通知；某一只没有上报时保留已有缓存，尚无缓存则显示未知，并继续后续请求。最后读取充电盒，按次打开的 SPP 链路在本轮命令结束后关闭；显示的三个电量值不保证都来自本轮刷新。

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
| 音量修复 | 开 | 解除非零 VCS 音量被抬至 16 的限制，让更低音量可用，并丢弃重复的绝对音量写入 |
| 详细日志 | 关 | 开启 UUID、音量、RACE 和交接 trace；默认关闭，不影响行为 hook |

「自动切换到 LE Audio」是下面那个问题的出口：用系统详情页的 LE Audio 开关关掉 LEA 后，ROM 会把音频回退到 LDAC，而 `onRemoteUuidsUpdated` 随后触发 `hookDirectLeaConnect`，把设备又升级回 LEA。关掉这个开关即可停止该行为，系统自带的 LE Audio 开关仍然有效。

两个界面的读写最终都通过 libxposed remote preferences 使用 `az100` 这个存储名：`MainActivity` 先写模块私有 SharedPreferences，再由模块自己经 libxposed service 发布到 remote preferences；Melody 和 Bluetooth hook 进程读同一份 remote preferences，provider 仍作为 Melody 设置页的跨 UID 接口，仅接受四个已知设置键，并校验调用方 UID（本模块、Melody、root）。框架服务不可用时，设置页仍保留本地副本，但目标进程只能读到旧值或默认值。

自动升级、双耳补连、音量修复和详细日志的默认值仍为开、开、开、关。这里的“已安装”与运行时开关要区分：自动升级和音量下限 / 重复写入判断会读取当前配置；但双耳补连 hook 只在蓝牙进程安装时开关为开才注册，音量重复写入 guard 也只在安装时开关为开才注册，详细日志的部分诊断专用 hook 同样在启动时决定。若这些功能在进程启动时关闭，之后把开关打开需要重新加载对应目标进程；本地保存成功不能补装 hook。稳定版 adb 实验控制台仍关闭。

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

确认交接后，模块停止自动升级、补连另一只耳塞和重新激活 group，也取消已排队的输出重设。只有 LE Audio 已在实际播放时，才断开 classic A2DP；退出独占状态只清掉本地标记，不主动重连手机 A2DP。

让出状态有不同的解除条件：普通 API 释放或 group 停用只开启 30 秒临时窗口；MEDIA 撤回、blocked-media 建议或 group 自主停用确认的远端接管没有自动超时，可由同一 group 恢复 MEDIA 或手机明确选择 AZ100 解除；若已收到 LE ACL 的 HCI `0x14` 资源释放，则必须在手机上明确连接 / 选择，后续 MEDIA 报告不能解除。手动断开同样必须由手机明确选择解除。

验证：先让手机通过 LE Audio 连接，再在电脑上连接并播放，等待至少 35 秒，确认声音留在电脑；随后在手机上选择耳机，确认 LE Audio 可以恢复。交接后不应有模块发起的 `A2DP reconnect requested` 或 `leaExclusiveOn`。

仓库现有 **53 个 JVM 测试用例**：协议解析 13 个、hook 回调 9 个、连接门控 3 个、交接 / 断开状态 23 个、原生地址门控 5 个。2026-10-16 的 Gradle 验证全部通过，见「构建」。这些测试不覆盖 LSPosed 的入口加载、hook 的实际安装、跨进程配置同步或原生补丁的指令编码（后者靠反汇编和实机 `native: patch rc=` 日志确认）。

历史验证：2.0.8 曾在 **PLJ110 / Android 17 / PLJ110_17.0.0.101(CN01)** 验证入口、native patch 和 LE Audio active 输出；随后用户报告临时音频探针导致黑屏及无法重启，相关测试方式已在 `AGENTS.md` 禁止。2.0.9 曾通过离线测试及 debug/release 构建并覆盖安装；**2026-10-14 22:14:58 的 Bluetooth 入口日志确认加载 2.0.9 / 43、libxposed API 102，用户随后确认使用正常。** 这不构成 2.0.10 的验证。安装和开关蓝牙均不保证目标进程退出；需结合本次进程的版本日志确认加载，详细日志关闭时不要依赖旧 trace 文件。

此前仍运行 2.0.8 时，用户确认手动断开有效，但电脑没有音频。对应快照中手机 native group 为 IDLE、CIG=NONE、CIS=0；Mac 已完成 AAC 配置，但 AVDTP 的最后一条媒体状态为 OPENING，CoreAudio 没有发布耳机输出。那次连接中菜单栏工具的自动 RFCOMM 读取与媒体建立并行，尚未证明其因果关系；历史证据见 [Mac 媒体通道排查](docs/2026-10-14_mac-audio-publish-stall.md)。

手动断开和电脑交接的修复与验证边界见 `docs/2026-10-14_manual-disconnect-and-handoff-fix.md`。回滚与挂载排查见 `docs/2026-10-14_modern-hook-and-audio-fix.md`；先前的电脑交接记录见 `docs/2026-10-14_lea-host-handoff-fix.md`。

### 设置里的开关

无线设置详情页的开关来自 `com.android.settings.bluetooth.BluetoothDetailsProfilesController`。`LeAudioProfile` 进 `mProfiles` 有两条路：

- LE Audio 变成 `STATE_CONNECTED` 时，`CachedBluetoothDevice.onProfileStateChanged()` 无条件加入。
- `updateProfiles()` 看到 `getUuids()` 含 `0000184e`。

只补 UUID 不够。设置进程可能在连接之后才打开，那时 `getUuids()` 仍是 null；LE Audio 必须真正连上，详情页重进后开关才稳定出现。

`LeAudioWhitelistHook` 只影响 Melody 自己的 BR/LE 视图。`com.android.settings` 不查 Melody 白名单，这个钩子和系统开关无关。

### 厂商栈里的另外两道门

- `libbluetooth_jni.so` 的 `oplus_btif_is_lea_device(RawAddress const&)` 对非 OPPO 产品返回 false，随后 `oplus_handle_filter_uuid()` 剥掉 `184E/184F/1846/1844`。该函数体本身不读入参，只按 `bt_config.conf` 的厂商/产品判定，所以 `NativeLeaDeviceHook` 在函数入口注入一段**按地址比较**的桩：地址属于 AZ100 时返回 true，否则跳回原函数体，OPPO 自家耳机和其他设备保持原行为。当前固件偏移是 `0x643180`，桩放在可执行段页尾的零填充区 `0x115ba80`；序言或代码洞不符时拒绝改写。系统升级后要重新核对偏移。
- LE 配对同意的抑制也在原生侧按地址做：`oplus_handle_le_audio_ssp_auto_consent(RawAddress const&)`（偏移 `0x65ce10`）对 AZ100 直接应答同意并返回 false，因此不弹对话框。早期版本改为伪造 `hasSystemFeature("android.software.leanback_only")`，那会让整个蓝牙进程在启动时就认定自己是 Android TV，且无法按设备收窄，已不再使用。
- `BassClientService.mOplusBassClientService` 在这版 ROM 上从未赋值。`184F` 放行后，`connect()` 会空指针并把整批 profile 连接打断。`BassClientFixHook` 补上实例；补不上时直接让这次 BASS 连接返回 false。

`SPLICE_LE_AUDIO_UUIDS` 仍是设置开关的兜底：它在 `setUuidsLe()` 入参里补 `0000184e`。这个注入不依赖「详细日志」。后者只控制日志：UUID 流水线、音量诊断，以及 `LeAudioTransportHook` 里的 `setDeviceVolume` 记录。音量修复本身（`allowVcp`、连接策略放行、音量上报捕获、成员生命周期）是无条件安装的，关掉「详细日志」不会让它失效。

## 代码

| 文件 | 进程 | 作用 |
| --- | --- | --- |
| `ModernXposedEntry` | 入口 | libxposed API 102 的 `XposedModule`，按作用域安装 hook |
| `HookRuntime` / `HookCall` | 适配层 | KavaRef 成员选择、libxposed 注册及 before/after 回调桥接 |
| `Az100Application` / `Prefs` | 模块应用 / 配置 | 初始化私有设置；hook 侧读取已 attach 的 remote preferences |
| `Az100Hook` | 配置 | 两条作用域、AZ100 设备识别和协议常量 |
| `MelodyProviderHook` | Melody | 应答音量面板 query 和点击 |
| `MelodyDetailBatteryHook` | Melody | 详情页手动刷新电量 |
| `DirectAirohaController` | Melody | 按次打开 SPP，发完即关 |
| `AirohaRace` | Melody | H4/RACE 分帧 |
| `LeAudioWhitelistHook` | Melody | Melody 内部 BR/LE 视图，不影响系统开关 |
| `LeAudioTransportHook` | 蓝牙 | LE 配对、UUID 注入、音量下限和双耳补连 |
| `NativeLeaDeviceHook` | 蓝牙 | 按地址打开 native LE Audio 门并抑制 LE 配对同意弹窗 |
| `BassClientFixHook` | 蓝牙 | 修补 BASS 空指针 |
| `OplusDualModeCoexistHook` | 蓝牙 | 直连 LE Audio，并阻止 classic 抢回 active |
| `LeaHandoffState` | 蓝牙 | 区分临时让出、远端接管和手动断开；HCI `0x14` 接管须由手机明确选择解除 |

`NativeLeaWhitelistHook` 保留但未安装。它写的是地址键，native 门实际按厂商/产品 ID 查，两者对不上。

## 验证

```bash
adb shell su -c 'grep -E "directLeaConnect|native: patch rc|bass-npe" /data/misc/bluetooth/az100.log'
adb shell su -c 'dumpsys bluetooth_manager | grep -A3 "84:85"'
```

期望：

- `native: patch rc=0` 与 `native: consent patch rc=0`（`rc=1` 表示本次进程里已经打过；`rc=-2` 序言不符，`rc=-4` 写入未生效）
- `bass-npe: injected OplusBassClientService`
- 两个 AZ100 地址都有 `directLeaConnect`
- `[LE UUIDs]` 含 `0000184e`
- LE Audio 连接后 `[ACL ... LE:Y]`
- `AdapterService --print` 中 `currentlyActiveGroupId` 有效且 `mLeAudioActiveDevice` 为 AZ100；只看到连接数不够

```bash
adb shell su -c 'dumpsys activity service com.android.bluetooth/.btservice.AdapterService --print'
```

这些命令只检查现有状态。active group、连接数或播放帧计数都不能证明可听声音或设备稳定；播放和听音必须由用户通过正常应用完成，不使用临时 DEX、`app_process`、辅助 APK 或独立 AudioTrack / MediaPlayer 探针。

**尚未验证：LE 配对同意抑制。** 2026-10-16 的实机检查里耳机已是已配对状态，`oplus_handle_le_audio_ssp_auto_consent` 的注入没有走到真实配对流程；确认它需要解绑后重新配对 AZ100，观察是否还弹同意对话框。同一次检查确认了：两个原生补丁的 `rc=0`、`/proc/<pid>/mem` 中四处桩字节与源码一致、53 个 JVM 测试通过、`zipalign -P 16` 通过、trace 中无未脱敏的完整 MAC。

`persist.bluetooth.*` 属于 `bluetooth_prop`。`adb shell getprop` 可能返回空，读的时候用 `adb shell su -c "getprop ..."`。

## macOS

`macos/` 用同一套 RACE 帧在 Mac 上切换降噪、读电量和读写耳机固件的 LE Audio 开关。它改的是耳机，不改手机里的系统设置。`macos/az100-routeb.sh` 则是旧的 adb 实验控制台客户端；当前 `LeAudioTransportHook.CONSOLE=false`，这些动作不属于可用的正式模块控制入口。

```bash
cd macos
./az100.sh
./az100.sh leaudio          # 读固件侧开关
./az100-menubar.sh
```

细节见 `macos/README.md`。协议字节见 `docs/audio-connect.md`。

根目录所有 `2026-*.md` 都是对应日期的排查证据，保留当时版本、实验开关和验证边界，不随当前迁移改写。使用和开发说明以本文件为准；判定实际运行版本仍以当前进程日志为准。

# Technics EAH-AZ100 适配

LSPosed 模块，当前版本 **2.0.3**（versionCode 36）。它做两件分开的事：

- 在 `com.oplus.melody` 里应答音量面板，并用自带的 Airoha SPP/RACE 读写降噪和电量。
- 在 `com.android.bluetooth` 里让 AZ100 走 LE Audio（LC3），并让系统设置出现 LE Audio 开关。

SystemUI、设备空间和 `com.panasonic.technicsaudioconnect` 都不注入。Audio Connect 可以卸载。

## 构建

仓库没有 Gradle Wrapper。本机用 JDK 21：

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home
gradle :app:assembleDebug -Dorg.gradle.java.home="$JAVA_HOME"
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。

JDK 25/26 会在 `JdkImageTransform` 失败。

## LSPosed

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

蓝牙进程里的 `XposedBridge.log` 经常丢，以 `az100.log` 为准。

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
| 详细日志 | 关 | 对应原来的 `DIAGNOSE` 常量，只影响日志 |

「自动切换到 LE Audio」是下面那个问题的出口：用系统详情页的 LE Audio 开关关掉 LEA 后，ROM 会把音频回退到 LDAC，而 `onRemoteUuidsUpdated` 随后触发 `hookDirectLeaConnect`，把设备又升级回 LEA。关掉这个开关即可停止该行为，系统自带的 LE Audio 开关仍然有效。

配置存在 LSPosed 重定向后的位置，权限 `0664`：

```
/data/misc/apexdata/<uuid>/prefs/com.tosasitill.az100/az100.xml
```

不是模块自己的 data 目录 —— 那里没有 `shared_prefs`，这是实测结果。蓝牙进程用 `XSharedPreferences` 直接读文件（它不能 bind 到模块，钩子必须在模块没运行时也能工作）；Melody 进程用不了文件（不同 UID，目录 `0700`），走 `Az100ConfigProvider.call()` 由模块代写。写入用 `commit()`，因为调用方是另一个进程，`apply()` 的异步落盘会让「已保存」撒谎。

改动在下次连接时生效，不需要重启蓝牙或重启手机。

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

### 手机 LE Audio 与电脑交接

电脑连接时，耳机仍可能保持手机的 LE ACL；这不代表手机仍拥有媒体流。模块监听 MEDIA 上下文撤回、blocked-media 建议、group 主动停用，以及耳机用 HCI `0x14` 释放 LE 资源。group 释放不再被连接后的 15 秒保护拦住。

确认交接后，模块停止自动升级、补连另一只耳塞和重新激活 group，也取消已排队的输出重设。只有 LE Audio 已在实际播放时，才断开 classic A2DP；退出独占状态只清掉本地标记，不主动重连手机 A2DP。这个状态持续到耳机重新提供 MEDIA 上下文，或用户在手机上明确连接 / 选择 AZ100；不会在 30 秒后自行抢回。

验证：先让手机通过 LE Audio 连接，再在电脑上连接并播放，等待至少 35 秒，确认声音留在电脑；随后在手机上选择耳机，确认 LE Audio 可以恢复。交接后不应有模块发起的 `A2DP reconnect requested` 或 `leaExclusiveOn`。

2026-10-14 的自动化验证覆盖 10 项交接状态测试。2.0.3 已在 OPPO Find X9 上验证：重启后不再因启动保护拆掉 A2DP，电脑可以接管。详情见 `2026-10-14_lea-host-handoff-fix.md`。

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
| `Az100Hook` | 入口 | Melody 走音量面板；蓝牙进程走 LE Audio |
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

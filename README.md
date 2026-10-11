# Technics EAH-AZ100 音量面板适配

LSPosed 模块，只注入 `com.oplus.melody`。音量二级菜单的降噪磁贴由 SystemUI 通过
`content://com.oplus.melody.provider.EarphoneControlProvider` 读取，模块在 Melody
进程内应答 query/call，并用自带的 Airoha SPP/RACE 协议下发模式。SystemUI 和
设备空间都不注入。

不加载、不反射 `com.panasonic.technicsaudioconnect`。Audio Connect 可以卸载。

长按磁贴会进入 Melody 的 AZ100 耳机详情页。模块保留 Activity/工具栏，跳过不支持
AZ100 的原生产品加载，提供连接状态、左右耳和充电盒电量，以及“立即刷新电量”按钮。
刷新时先读取主耳方向，再等待电量请求的 ACK 和异步通知，收到数据后才关闭 SPP。

## 构建

```bash
gradle :app:assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。

## LSPosed

1. 安装并启用模块。
2. 作用域只勾 `com.oplus.melody`。不要勾 `com.heytap.mydevices` 或
   `com.android.systemui`。
3. `adb shell am force-stop com.oplus.melody`。

日志：

```bash
adb logcat -s AZ100:V
```

## 代码

| 文件 | 作用 |
| --- | --- |
| `Az100Hook` | 只在 `com.oplus.melody` 加载，转给 `MelodyProviderHook` |
| `MelodyProviderHook` | 应答音量面板的 active/noise query 和点击 |
| `MelodyDetailBatteryHook` | 在 Melody 耳机详情页提供手动电量刷新 |
| `LeAudioWhitelistHook` | 让 Melody 把 AZ100 认成支持 BR/LE 切换（实测生效，但与系统设置那个开关无关，可关） |
| `DirectAirohaController` | 按次打开 SPP，发完即关 |
| `AirohaRace` | H4/RACE 分帧 |

面板模式：`5` 降噪、`1` 关闭、`2` 通透。自适应不出现在轮换里，耳机若处于自适应，
显示为降噪，下一次切换会先清掉。

耳机连上后最多读一次当前模式和电量。音量面板之后的 query 只回内存里的模式，
不会因为 SystemUI 的媒体路由回调反复建立 SPP。

## LE Audio / LC3

> **✅ 15:15 收官（已打通）**：AZ100 现在**真的跑在 LE Audio 上**，不再是「只让 UI
> 出现开关」。模块 `LeAudioTransportHook`（v1.5.1 / versionCode 10，
> `SPLICE_LE_AUDIO_UUIDS = true`）改 `RemoteDevices$DeviceProperties#setUuidsLe`
> 的实参，把 `0000184e` 补进 `mUuidsLe`（仅对目标地址）。实测：
> `[LE UUIDs]: 0000180f-… 0000184e-…`、`le_audio: ALLOWED`、
> `LeAudioStateMachine state=Connected`、`currentlyActiveGroupId: 1`、
> ASE 走到 `STREAMING (0x04)`（`CODEC_CONFIG: snk,6,3;;` = LC3）。
> 完整证据链见报告 §12。

**结论（真机实测，2026-10-11 定案）：设置里没有这个开关，跟白名单无关。
唯一决定点是 `BluetoothDevice.getUuids()` 里有没有 LE Audio 服务 UUID `0000184e`，
没有的话 Settings 连开关控件都不会创建。**

拿不到 `0000184e` 有两个前后相继的原因，**两个都已解决**：

1. ~~耳机在 Classic Audio 模式，根本不广播 `0000184e`~~ → **已解决**（13:20 起在广播了）。
2. ~~**手机不建立 LE 链路**：`createBond()` 用 `transport=0`(AUTO)，AZ100 的 LE 广播
   没有置 `BR/EDR Not Supported` 标志位（`NOT_BR_EDR support bit not set, treat device as DUMO`），
   于是走 BR/EDR 配对 + OPPO 的 `oplus_delay_ble_smp_over_br`(CTKD) 派生 LE 密钥。~~
   → **已解决**：强制 LE 配对（`FORCE_LE_TRANSPORT`）拿到 LE bond。

   但注意：LE 链路建起来之后 `mUuidsLe` 里**仍然只有 `0000180f`** —— 厂商栈在
   `oplus_btm_ble_process_adv_pkt_cont` 里以 `removal of lea uuids` 把 LEA UUID
   剥掉了（设备不在它的 `isLEAudioDeviceInWhiteList` 里）。**这才是最后一环**，
   所以必须显式补 UUID，仅靠「让手机走 LE」不够。

唯一决定点（`LocalBluetoothProfileManager#updateProfiles`，第 493 行）：

```java
if (ArrayUtils.contains(parcelUuidArr, BluetoothUuid.LE_AUDIO) && (leAudioProfile = this.mLeAudioProfile) != null) {
    collection.add(leAudioProfile);      // ← 只有这里会把 LeAudioProfile 塞进设备
}
```

`parcelUuidArr` = `device.getUuids()`，它等于 `mUuidsBrEdr ∪ mUuidsLe`（**不含 LE 广播里的 UUID**，
`RemoteDevices$DeviceProperties#getUuids()`，`RemoteDevices.java:498`）。AZ100 的
`[BR/EDR UUIDs]` 里没有 `0000184e`，`[LE UUIDs]` 整行不存在 → 没有它 → `LeAudioProfile`
不进 `mProfiles` → `getUiAccessibleProfiles()` 里没有它 → `BluetoothDetailsProfilesController`
不创建这个 preference。

协议栈侧是同一个条件，且只是四道门里的第一道：

```java
Util.arrayContains(uuids, BluetoothUuid.LE_AUDIO)                 // (A) 挂了
  && leAudioService.getConnectionPolicy(device) != 0
  && (mLeAudioEnabledByDefault || Utils.isDualModeAudioEnabled())  // 已满足
  && (bypass || hapDefault || isLeAudioAllowed(device) || ...)     // 已满足（bypass=true）
```

**OPPO 自己的 LE Audio 策略整类也是同一个前提** —— 反编译
`/system_ext/framework/oplus_bluetooth_common_ext.jar` 里的
`com.oplus.bluetooth.leaudio.OplusLeAudioPhonePolicyExt`：

```java
private void initLeAudioPriority(boolean autoConnectProfilesSupported,
                                 BluetoothDevice device, ParcelUuid[] uuids) {
    if (mAdapterService != null && getLeAudioService() != null
        && containsUuid(uuids, BluetoothUuid.LE_AUDIO)     // ★ 每个分支都有这道守卫
        && leAudioService.getConnectionPolicy(device) == -1) {
        setProfilePolicy(device, 22, autoConnectProfilesSupported ? 100 : 0);
    }
}
```

所以 `com.flyfish233.coloros.leaudiofx`（v0.6.6，hook 的正是 `initLeAudioPriority`）
在当前状态下**必然空转** —— 它改得了策略值，改不出 `LeAudioProfile`、更建不出 LE ACL。

> 顺带纠正：`persist.bluetooth.*` 属性属于 `bluetooth_prop`，**`adb shell` 无权读**，
> `getprop` 会静默返回空。读蓝牙属性一律用 `adb shell su -c "getprop ..."`。

完整证据链、每条属性的实际值、LSPosed 模块注入日志、OPPO 策略源码与下一步路线见
**[`2026-10-11_leaudio-whitelist-report.md`](2026-10-11_leaudio-whitelist-report.md)**。

代价（如果最终真切到 LE Audio）：失去 LDAC、多点连接降到 1 台、Spatial Audio / 语音助手关闭。
**如果目标是音质，LE Audio 是负优化**；它的价值在低延迟 / Auracast / 助听器互通。

`LeAudioWhitelistHook` 实测全部生效（`Melody_FeatureOptions: ... sLeaWhiteListEnable = true,
sBrLeCoExist = true`），但它**与系统设置那个开关无关**（Settings 从不查 Melody 白名单），
建议用文件里的 `ENABLED` 常量关掉。

耳机固件侧的开关是另一条链路，走 Airoha `RaceIdPana` 89/90，由 macOS 侧工具操作。
它很可能就是「切 LE Audio 模式」的同一个开关（切完会断链重连）：

```bash
./az100.sh leaudio          # 读
./az100.sh leaudio on       # 写
```

## macOS 侧工具

`macos/` 下有一套同协议的命令行工具和菜单栏 App，方便在 Mac 上直接切换降噪模式、
看电量（用 `IOBluetooth` 走 RFCOMM，协议实现从 `AirohaRace` 移植）。

```bash
cd macos
./az100.sh                      # 看模式 + 电量
./az100.sh anc                  # 切降噪（还有 通透 / 关闭 / 自适应 / toggle）
./az100.sh leaudio on           # 开耳机固件侧的 LE Audio（LC3）
./az100-menubar.sh              # 构建并安装菜单栏 App 到 ~/Applications
./az100-menubar.sh login        # 设为登录自启
```

菜单栏 App 在耳机连上这台 Mac 时自动出现、断开时自动消失。细节见 `macos/README.md`。

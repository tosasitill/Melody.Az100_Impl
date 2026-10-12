# AZ100 的 LE Audio / LC3 开关：为什么系统设置里没有（2026-10-11 排查记录）

> 这是 2026-10-11 的记录。当时的“下一步”和“唯一缺失环节”已过时。
> 当前行为以仓库根目录的 `README.md` 为准：UUID 注入仍在，另外由 native 门、BASS 空指针修复和
> `connectLeAudio()` 直连负责真正连上 LE Audio。

> **2026-10-11 13:45 结论修订**：耳机侧已经改好了 —— 从 13:20 起 AZ100 **确实在 LE 广播里
> 喊 `0000184e`** 了。卡点已经从「耳机」移到「手机不建 LE 链路」。详见 **§8.6**。
> 下面 §0–§8 的原始结论仍然成立（当时耳机确实在 Classic 模式），但不再是当前瓶颈。

**一句话（修订后）**：耳机已经在广播 LE Audio 了，但 **手机仍然按双模经典设备配对
（`transport=0/AUTO` → BR/EDR + SMP over BR 派生 LE 密钥），从头到尾不建立 LE ACL**。
没有 LE ACL → 没有 LE 侧服务发现 → `mUuidsLe` 为空 → `BluetoothDevice.getUuids()` 里
永远没有 `0000184e` → `LeAudioProfile` 不会被创建 → **系统设置里那个开关永远不会出现**。

**原始结论（13:02 版，仍然有效）**：手机侧和系统设置都靠 `0000184e` 这个 UUID 判断
「这台设备支持 LE Audio」，拿不到就**连开关控件都不会创建**。当时 AZ100 还在 Classic
Audio 模式，所以拿不到；现在耳机侧已经打开了，但手机侧不建 LE 链路，仍然拿不到。

---

## 0. 证据链总览

| 环节 | 结论 | 证据 |
| --- | --- | --- |
| 耳机是否支持 LE Audio | ✅ 支持（TMAP / PBP / LC3） | Technics 官方规格 + 官方 FAQ |
| 耳机当前是否在 LE Audio 模式 | ❌ 在 Classic Audio 模式 | `Codecs = SBC,AAC,LDAC`（含 LDAC，LE Audio 模式下 LDAC 不可用） |
| 手机是否收到 `0000184e` | ❌ 没有 | SDP UUID 列表、`bt_config.conf`、`dumpsys` 全文 0 处 `184e` |
| Settings 是否创建开关 | ❌ 没创建 | 反编译 `LocalBluetoothProfileManager.updateProfiles()` 第 493 行 |
| 协议栈是否放行 | ❌ 第一道门就挂 | `PhonePolicy#processInitProfilePriorities` 第 1 个 AND 条件 |
| Melody 白名单是否相关 | ❌ 无关（红鲱鱼） | provider 日志显示 Settings 从不查 Melody |
| 之前设的属性是否生效 | ✅ 生效了 | 需用 `su` 读，`shell` 无权读 `bluetooth_prop` |

---

## 1. 关键认知纠错：`getprop` 读不到 ≠ 属性没设

上一轮排查中 `persist.bluetooth.*` 读出来是空，一度以为方案 B 失败。**这是假象**：
`persist.bluetooth.*` 属于 SELinux 的 `bluetooth_prop`，**`adb shell`（shell 域）无权读**，
`getprop` 静默返回空字符串。用 `su` 读就正常了：

```bash
adb shell su -c "getprop persist.bluetooth.enable_dual_mode_audio"       # true
adb shell su -c "getprop persist.bluetooth.leaudio.bypass_allow_list"    # true
```

对照 `LeAudioService` 的 dumpsys 输出 `isDualModeAudioEnabled: true` 也印证了这一点。

> 教训：排查蓝牙属性**一律用 `su -c` 读**，否则会得到系统性的假阴性。

---

## 2. 系统设置里的开关是怎么被决定的

### 2.1 第一层：Settings 侧（决定「控件存不存在」）

`com.android.settings.bluetooth.BluetoothDetailsProfilesController`：

```java
private void updateLeAudioConfig() {
    boolean z3 = SystemProperties.getBoolean("persist.bluetooth.leaudio.toggle_visible", true);
    boolean z4 = SystemProperties.getBoolean("ro.bluetooth.leaudio.le_audio_connection_by_default", true);
    this.mIsLeAudioToggleEnabled = z3 || z4;     // 两个属性都未设置 → 默认 true
}

private void refreshProfilePreference(TwoStatePreference pref, LocalBluetoothProfile profile) {
    if (profile instanceof LeAudioProfile) {
        boolean vis = this.mIsLeAudioToggleEnabled;
        if (this.mIsLeAudioOnlyDevice) vis = false;
        pref.setVisible(vis);
    }
}
```

两个属性在这台机器上都**未设置 → 默认 `true`**，所以 `mIsLeAudioToggleEnabled = true`。
**可见性不是问题。问题在于这个 `SwitchPreference` 根本不会被创建。**

创建逻辑在 `lambda$refresh$5()`：

```java
for (LocalBluetoothProfile profile : getProfiles()) {
    if (profile != null && profile.isProfileReady()) {
        TwoStatePreference pref = (TwoStatePreference) mProfilesContainer.findPreference(profile.toString());
        if (pref == null) {
            pref = createProfilePreference(mProfilesContainer.getContext(), profile);  // ← 只在这里 new
            mProfilesContainer.addPreference(pref);
        }
        refreshProfilePreference(pref, profile);
    }
}
```

而 `getProfiles()` 遍历的是 `cachedBluetoothDevice.getUiAccessibleProfiles()`：

```java
// CachedBluetoothDevice.java:1040
public List<LocalBluetoothProfile> getUiAccessibleProfiles() {
    ArrayList arrayList = new ArrayList();
    synchronized (this.mProfileLock) {
        for (LocalBluetoothProfile localBluetoothProfile : this.mProfiles) {
            if (localBluetoothProfile.accessProfileEnabled()) {   // LeAudioProfile 恒返回 true
                arrayList.add(localBluetoothProfile);
            }
        }
    }
    return arrayList;
}
```

`LeAudioProfile.accessProfileEnabled()` 恒定返回 `true`（`LeAudioProfile.java:117`），
所以过滤条件不是问题 —— **问题在于 `mProfiles` 里压根没有 `LeAudioProfile` 这个对象**。

### 2.2 第二层：`mProfiles` 是怎么填的（真正的闸门）

`CachedBluetoothDevice.updateProfiles()`（第 770 行）：

```java
private boolean updateProfiles() {
    ParcelUuid[] uuids = this.mDevice.getUuids();     // ← SDP 得到的 UUID 列表
    if (uuids == null) return false;
    ...
    this.mProfileManager.updateProfiles(uuids, parcelUuidArr, this.mProfiles, ...);
    return true;
}
```

`com.android.settingslib.bluetooth.LocalBluetoothProfileManager#updateProfiles`（第 478 行）：

```java
public synchronized void updateProfiles(ParcelUuid[] parcelUuidArr, ParcelUuid[] parcelUuidArr2,
        Collection<LocalBluetoothProfile> collection, Collection<LocalBluetoothProfile> collection2,
        boolean z2, BluetoothDevice bluetoothDevice) {
    ...
    if (ArrayUtils.contains(parcelUuidArr, BluetoothUuid.LE_AUDIO) && (leAudioProfile = this.mLeAudioProfile) != null) {
        collection.add(leAudioProfile);          // ★ 只有这一行会把 LeAudioProfile 塞进设备
        collection2.remove(this.mLeAudioProfile);
    }
    ...
}
```

**`parcelUuidArr` 就是 `BluetoothDevice.getUuids()`，即设备 SDP 广播的服务 UUID 列表。
`0000184e` 不在里面 → `LeAudioProfile` 永远不进 `mProfiles` → Settings 永远不创建开关。**

这是**唯一**的决定点。`persist.bluetooth.leaudio.toggle_visible`、白名单、`brLeCoExist`、
任何属性都改不了它。

---

## 3. 实测：AZ100 的 SDP 里没有 `0000184e`

`bt_config.conf`（`/data/misc/bluedroid/bt_config.conf`，段 `[b8:20:8e:ee:84:85]`）：

```ini
[b8:20:8e:ee:84:85]
LeAudioAutoconnect = 0
Name = Technics EAH-AZ100
Appearance = 2369                                   # 0x0941 = Earbud
LePairingAlgorithm = 4                              # LE 已配对（SC）
Service = 00000000-0000-0000-0099-aabbccddeeff      # Airoha SPP
          00001108-0000-1000-8000-00805f9b34fb      # Headset (HFP-HF)
          0000110b-0000-1000-8000-00805f9b34fb      # AudioSink (A2DP)
          0000110e-0000-1000-8000-00805f9b34fb      # A/V Remote Control
          0000111e-0000-1000-8000-00805f9b34fb      # Handsfree (HFP-AG)
          0000ff01-0000-1000-8000-00805f9b34ff
          32bf2fe6-297c-4ceb-839c-1bf26a1155ac
          5052494d-2dab-0341-6972-6f6861424c45      # "PRIM-…"
Codecs = SBC,AAC,LDAC                               # ★ 有 LDAC = 一定在 Classic Audio 模式
SelectedCodecs = LDAC
```

- **没有 `0000184e`**（LE Audio / ASCS）。
- **`Codecs` 里有 LDAC** —— 这是判定 Classic Audio 模式的**铁证**：Technics 官方 FAQ 明确写
  「When connected via LE Audio, the setting of "Connection mode" is disabled (**LDAC cannot be used**)」。

`dumpsys bluetooth_manager` 全文 2027 行，`grep -c -i 184e` = **0**。

同一个条件在协议栈里是**第一道门**，两边完全一致：

```java
// com.android.bluetooth.btservice.PhonePolicy#processInitProfilePriorities
boolean allowed = leAudioService.isPresent()
    && Util.arrayContains(uuids, BluetoothUuid.LE_AUDIO)              // ← 这一条就挂了
    && leAudioService.getConnectionPolicy(device) != 0
    && (mLeAudioEnabledByDefault || Utils.isDualModeAudioEnabled())   // 已满足（true）
    && (bypass || hapDefault || mAdapterService.isLeAudioAllowed(device) || isLeAudioOnly);  // 已满足（bypass=true）
```

**所以「A 重新配对 + B 设属性 + 重启」注定无效** —— 后三道门早就开了，第一道门永远关着。

---

## 4. 为什么 SDP 里没有？—— 因为耳机在 Classic Audio 模式

Technics / Panasonic 官方支持页（EAH-AZ100 FAQ）：

> **LE Audio Connection Settings for EAH-AZ100**
> In the home screen of the [Technics Audio Connect] app, tap [SETTINGS].
> Tap [LE Audio Connection Settings]. Select **[Prioritize LE Audio]**.
> Follow the on-screen instructions.
> **All pairing information for the earphones will be erased.** When the pairing sound (melody)
> plays, unpair this unit from the Bluetooth menu in the Bluetooth device, and pair again.

> **Changing the settings of LE Audio Connection will clear all pairing information stored in the
> earphones. You need to pair the earphones again every time you change it.**

> When connected via LE Audio, the setting of below items will be fixed as below:
> Number of multi-point connections: **1**；Spatial Audio: **Off**；Voice assistant: **Off**
> … LDAC cannot be used … Google Fast Pair / Swift Pair disabled.

**AZ100 出厂默认是 Classic Audio 模式**，LE Audio 是耳机侧的**模式开关**，只能在 Technics App 里切。
切之前，无论手机侧怎么折腾，耳机都不会广播 `0000184e`。

这同时解释了为什么之前「重新配对」没用 —— **配对关系重建了，但耳机的工作模式没变。**

---

## 5. 要真正拿到这个开关，怎么做

### 步骤 1（必做）：把耳机切到 LE Audio 模式

任选其一：

- **(a) 官方途径（推荐）**：Technics Audio Connect App → SETTINGS → **LE Audio Connection Settings**
  → **Prioritize LE Audio** → 按提示操作。耳机会清空配对并进入配对模式。
- **(b) 本仓库的 SPP 通路（待验证是否等价）**：`macos/az100.sh leaudio on`
  （Airoha RACE `RID_SET_LE_AUDIO = 90`）。脚本里已注明「会断开重连一次」，
  行为特征与「切换 LE Audio 模式」一致，**很可能就是同一个开关**，但官方文档只保证 (a)。

### 步骤 2：重新配对

耳机进入配对模式后，手机蓝牙里删掉旧的 "Technics EAH-AZ100" 记录，重新配对。

### 步骤 3：验证

```bash
# 出现 0000184e 就说明通了
adb shell dumpsys bluetooth_manager | grep -A2 "Technics EAH-AZ100"

# 或者直接看 Settings 的判定日志
adb logcat -d | grep -iE "processInitProfilePriorities|Current Profiles"
```

只要 `0000184e` 出现，**开关会自动出现在「设置 → 蓝牙 → Technics EAH-AZ100」里**，
不需要再动任何属性（后三道门已经开着）。

### 步骤 4（仅在开关仍不出现时才需要）

如果 `0000184e` 出现了但开关还是没出现，再查：

```bash
# 开关可见性（两个都 false 才会隐藏；默认都是 true）
adb shell su -c "getprop persist.bluetooth.leaudio.toggle_visible"
adb shell su -c "getprop ro.bluetooth.leaudio.le_audio_connection_by_default"

# 协议栈那行完整诊断日志，逐项对照
adb logcat -d | grep processInitProfilePriorities
```

---

## 6. ⚠️ 切到 LE Audio 的代价（务必先权衡）

AZ100 在 LE Audio 模式下会**固定**变成：

| 项目 | Classic Audio（现在） | LE Audio |
| --- | --- | --- |
| 编解码器 | SBC / AAC / **LDAC** | **LC3**（LDAC 不可用） |
| 多点连接 | 支持多台 | **固定 1 台** |
| Spatial Audio | 可用 | **关闭** |
| 语音助手 | 可用 | **关闭** |
| Google Fast Pair / Swift Pair | 可用 | 关闭 |
| 通话 | 正常 | **部分通话 App 可能无法通话**（官方原文） |

另外：

- 切换会**清空耳机内所有配对信息**，必须重新配对；
- 想切回 Classic Audio 需要再走一次 App 设置（官方注明「某些情况下可能切不回来，
  需要把耳机恢复出厂设置」）；
- 当前 `SelectedCodecs = LDAC`，切过去等于**主动降级**。

**如果你的目标是「更好的音质」，LE Audio 反而是负优化。**
LE Audio 的价值在低延迟、广播（Auracast）、助听器互通。

---

## 7. 关于仓库里已实现的 `LeAudioWhitelistHook`

它做的事情**全部生效了**，设备日志可证：

```
Melody_FeatureOptions: init, sLeAudioSupport = true, metaSupport = true,
  sSpatialHogpSupport = true, sIsTrialVersion = false, sFlavorRegion = domestic,
  sLeaWhiteListEnable = true, sBrLeCoExist = true
```

但它**不解决系统设置里那个开关**：

- 系统设置（`com.android.settings`）**从不查 Melody 的白名单** —— provider 日志里调用方只有
  `com.android.bluetooth` 和 `com.oplus.exsystemservice`；
- Melody 的 4 个 `persist.sys.*` 属性在这台机器上**本来就是 `true`**，hook 是冗余的；
- `ears_whitelist` 返回的 `Item` 只有 `{name, pid, wear, type}`，**没有任何能力位**。

**结论：可以关掉它。** 文件里有开关：

```java
/** Flip to false and reinstall to leave Melody exactly as it was. */
private static final boolean ENABLED = true;
```

另外 Melody 自己也认为 AZ100 不是 LE 设备：

```
Melody_btsdk_m_bt_le.LeAudioDeviceManager:
  requestLeAudioInfoBroadcast, is not le device , mac=B8:20:*****:84:85
```

---

## 8. 排查工具箱（本次用到，可复用）

| 目的 | 命令 |
| --- | --- |
| 读蓝牙属性（**必须 su**） | `adb shell su -c "getprop \| grep -iE 'leaudio\|le_audio\|dual_mode'"` |
| 看设备 SDP UUID | `adb shell dumpsys bluetooth_manager \| grep -A2 "BR/EDR UUIDs"` |
| 看配对配置原始文件 | `adb shell su -c "sed -n '/b8:20:8e:ee:84:85/,/^$/p' /data/misc/bluedroid/bt_config.conf"` |
| 协议栈 LE Audio 判定 | `adb logcat -d \| grep processInitProfilePriorities` |
| Settings 的 profile 列表 | `adb logcat -d \| grep "Current Profiles"` |
| 打开设备详情页 | `adb shell 'am start -n "com.android.settings/.Settings\$BluetoothDeviceDetailActivity" -e device_address <MAC>'` |
| 读 DeviceConfig | `adb shell device_config get bluetooth le_audio_enabled_by_default` |

反编译单个类（207MB 的 Settings.apk 约 13 秒）：

```bash
jadx --single-class com.android.settingslib.bluetooth.LocalBluetoothProfileManager -d /tmp/out Settings.apk
```

---

## 8.5 复核记录：在 Technics App 里选了「Prioritize LE Audio」之后

用户按 App 提示切换到 **Prioritize LE Audio**（App 弹了「会清空耳机配对信息」的提示），
并重新配对。复核结果：**手机侧依旧完全没有 LE Audio 的痕迹。**

| 检查项 | 结果 |
| --- | --- |
| `bt_config.conf` LinkKey | 已变（`1289166b…` → `3d8447…`）→ **确实重新配对过** |
| `Service`（SDP UUID） | 9 条，**仍无 `0000184e`** |
| `Codecs` | `SBC,AAC,LDAC`（LDAC 仍在） |
| `LeAudioAutoconnect` | `0`（未变） |
| 配对走的传输 | `Ssp reply … accept:Y **le:F**` → **BR/EDR 配对** |
| 配对后 | `API Discovered GATT services : …` → **空**；`SDP Discovered services … sdp_uuids:10 eir_uuids:0` |
| ACL | `ACL BR/EDR:Y **LE:N**`；`shim::acl remote_addr:…[BT_TRANSPORT_BR_EDR]` —— **没有任何 LE ACL** |
| 音频通路 | `A2DP Stream opened … BTA_AV_OPEN_EVT` —— 走经典 A2DP |
| 原生栈 | `bta_dm_obtain_system_context: … le_acl_num 0 … le_audio:[false]` |
| `dumpsys` | AZ100 只有 `[BR/EDR UUIDs]`，**没有 `[LE UUIDs    ]`** |
| `LeAudioService event log` | `12:55:22.317 [From Native]: groupId: -1, status: Inactive` |

**手机侧不是瓶颈**：`Enabled Profile Services` 已含 `LE_AUDIO`，
`persist.bluetooth.enable_dual_mode_audio` / `bypass_allow_list` 均为 `true`，
Technics App 也是最新版 5.0.1。

### 底层机制补充（本次新查明）

`com.android.bluetooth.btservice.RemoteDevices$DeviceProperties` 有**四个** UUID 数组：

```java
ParcelUuid[] mUuidsBrEdr;                      // SDP
ParcelUuid[] mUuidsFromExtendedInquiryResponse;
ParcelUuid[] mUuidsFromLeAdvertisingData;      // LE 广播里的 Service UUID
ParcelUuid[] mUuidsLe;                         // LE 侧发现的服务
```

而 `getUuids()` **只并前两个中的 BR/EDR 和 `mUuidsLe`**（`RemoteDevices.java:498`）：

```java
public ParcelUuid[] getUuids() {
    ParcelUuid[] a = this.mUuidsBrEdr;
    ParcelUuid[] b = this.mUuidsLe;
    if (len(a) + len(b) == 0) return a;
    LinkedHashSet set = new LinkedHashSet();
    if (a != null) for (ParcelUuid u : a) set.add(u);
    if (b != null) for (ParcelUuid u : b) set.add(u);
    return set.toArray(new ParcelUuid[set.size()]);
}
```

`getUuidsFromLeAdvertisingData()` **只被 `AdapterService#prepareDiscoveryResultIntent` 使用**
（塞进扫描结果的 Intent），**不参与 `BluetoothDevice.getUuids()`**。
所以「耳机在 LE 广播里喊 184E」还不够，**必须真正建立起 LE 链路、让协议栈把 LE 侧服务写进 `mUuidsLe`**，
Settings 才看得到。

`BluetoothDevice.dump()` 会分别打印 `[BR/EDR UUIDs]` 与 `[LE UUIDs    ]`
（字面量在 `RemoteDevices$DeviceProperties#toString`）。**只出现前者 = 该设备没有任何 LE 信息。**

### 因此下一步

问题出在**耳机没有以 LE 身份和手机建立连接**，而不是手机不认。建议按顺序重来：

1. **先确认 App 里的单选状态**：SETTINGS → LE Audio Connection Settings，看当前勾的是哪一项。
2. **两端都清干净**：手机上删除 AZ100（确认连 LE 那条一起清掉）；耳机做一次**出厂重置**。
   官方 FAQ 明确写了模式切换失败时需要恢复出厂设置：
   > In some cases, the setting may not change from LE-Audio to Classic audio.
   > In that case, please reset the earbuds to factory setting by referring to Owner's Manual.
3. **重新配对时按官方要求操作**：两只耳机都要**从充电盒取出**
   （官方原文：*When connected via LE Audio, please remove both earphones from the charging cradle.*），
   然后配对。
4. **验证**（出现 `[LE UUIDs    ]` 且含 `0000184e`、`ACL … LE:Y` 才算成功）：
   ```bash
   adb shell dumpsys bluetooth_manager | grep -A3 "Technics EAH-AZ100"
   adb shell dumpsys bluetooth_manager | grep -c -i 184e
   adb shell su -c "sed -n '/b8:20:8e:ee:84:85/,/^$/p' /data/misc/bluedroid/bt_config.conf"
   ```
5. 仍然不行 → 把耳机固件从 1.030 升到 App 提示的 **1.040**（App 显示未下载）再试。

> 不建议用「hook 蓝牙进程伪造 `0000184e`」的兜底方案：那只会让开关出现，
> 耳机并不会因此变成 LE Audio，打开开关反而可能把音频搞坏。

---

## 8.6 复核记录（13:45）：耳机侧已经 OK，卡点搬到手机侧

### 8.6.1 铁证：耳机从 13:20 起开始广播 LE Audio

原生蓝牙 verbose 日志在 `/data/misc/bluetooth/logs/bluetooth_*.log`。
按文件统计 `0x184E` 出现次数（`grep -c "0x184E"`）：

| 日志文件（时间） | `0x184E` 次数 |
| --- | --- |
| `...130939_07630.log` (13:09) | **0** |
| `...131033_08907.log` (13:10) | **0** |
| `...131407_17771.log` (13:14) | **0** |
| `...131502_03264.log` (13:15) | **0** |
| `...131552_16157.log` (13:15) | **0** |
| `...131648_17205.log` (13:16) | **0** |
| `...131919_23433.log` (13:19) | **0** |
| `...132013_03257.log` (13:20) | **6** ← 拐点 |
| `...132437_22727.log` (13:24) | 1 |
| `...132707_25541.log` (13:27) | 8 |
| `...132912_28168.log` (13:29) | 4 |

拐点日志里的完整上下文（确认归属 AZ100）：

```
[btm_ble_gap.cc:1226] btm_ble_process_adv_addr: bda=xx:xx:xx:xx:84:85 addr_type=0
[le_scanning_reassembler.cc:98] ProcessAdvertisingReport: Full advertising report has been reassembled
[bta_dm_device_search.cc:224] bta_dm_inq_results_cb: ... bda=xx:xx:xx:xx:84:85
[oplus_btm_ble_gap.cc:458] NOT_BR_EDR support bit not set, treat device as DUMO
[btm_inq.cc:1982] BTM_GetEirUuidList: type = 02, number of uuid = 5
[btm_inq.cc:1987] BTM_GetEirUuidList: 0x184E 0x184F 0x1850 0x1844 0x184D
[interop_oplus.cc:741] interop_oplus_match_name_impl:[OplusSkipDumoInfo] check ****nics EAH-AZ100 return 0
```

→ **`0000184e`(ASCS) / `184f`(BASS) / `1850`(PACS) / `1844`(VCS) / `184d`(MCS) 全在**，
而且是走 **LE 广播**（`btm_ble_process_adv_addr` → `ProcessAdvertisingReport`）进来的。
**用户在 Technics App 里的模式切换确实生效了。**

### 8.6.2 手机侧仍然走经典：配对全过程

```
13:36:29.776  btif_dm_create_bond: bd_addr=xx:xx:xx:xx:84:85, transport=0      ← AUTO
13:36:29.778  bond_state_changed: ...[BT_TRANSPORT_AUTO] → BONDING
13:36:39.778  btif_dm_auth_cmpl_evt: success=true, key_present=true
13:36:39.789  bond_state_changed: ...[BT_TRANSPORT_BR_EDR] → BONDED, bond_type: 1
13:36:39.812  oplus_delay_ble_smp_over_br: start smp                          ← OPPO 主动跑 SMP over BR
13:36:39.899  bond_state_changed: ...[BT_TRANSPORT_LE] → BONDING              ← 只为派生 LE 密钥
13:36:39.904  bond_state_changed: ...[BT_TRANSPORT_LE] → BONDED (SC)
```

`transport=0`(AUTO) + 设备是双模 → 走 BR/EDR；OPPO 再用 **SMP over BR (CTKD)** 派生 LE 密钥。
结果：`bt_config.conf` 里有 `LE_KEY_*`（LE 配对信息是有的），但 **LE ACL 从未建立**。

配对后 `dumpsys bluetooth_manager`：

```
[ DUAL ] [0x240404] [Pairing Algorithm BR/EDR: 1 LE: 3]
[ACL BR/EDR:Y LE:N] [ Encryption status(BR/EDR): ...] LE: N/A]  Technics EAH-AZ100
    [BR/EDR UUIDs]: 00000000-... 00001108-... 0000110b-... 0000110e-... 0000111e-...
                   0000ff01-... 32bf2fe6-... 5052494d-...
    [Packages    ]: [com.oplus.wirelesssettings]
```

- **`[LE UUIDs    ]` 整行都不存在** → `mUuidsLe == null`。
- 全文 `grep -c -i 184e` = **0**。
- `A2DP Stream opened` / `Codecs = SBC,AAC,LDAC` / `SelectedCodecs = LDAC` → 走的经典 A2DP。
- 原生栈：`active_acl_num 0 sniff_acl_num 1 le_acl_num 0 le_audio false`。
- `LeAudioService event log` 只有 `groupId: -1, status: Inactive`。

系统设置设备详情页（`com.android.settings/.Settings$BluetoothDeviceDetailActivity`）
实际渲染出来的是：**设备名称 / 断开连接 / 取消配对 / 用于 → 媒体音频（高品质 LDAC）、通话音频、
共享联系人 / 设备音量同步 / 设备 MAC 地址** —— **没有任何 LE Audio / LC3 条目**。

### 8.6.3 为什么 OPPO 的 LE Audio 策略也救不了：整条链都以 UUID 为前提

反编译 `/system_ext/framework/oplus_bluetooth_common_ext.jar`
（`com.oplus.bluetooth.leaudio.OplusLeAudioPhonePolicyExt`）：

```java
public void oplusInitProfilePriorities(boolean autoConnectProfilesSupported,
                                       BluetoothDevice device, ParcelUuid[] uuids) {
    ...
    initVolumeControlPriority(..., device, uuids);
    initLeAudioPriority(..., device, uuids);        // ← 见下
    initBatteryPriority(device, uuids);
    ...
}

private void initLeAudioPriority(boolean autoConnectProfilesSupported,
                                 BluetoothDevice device, ParcelUuid[] uuids) {
    LeAudioService leAudioService;
    if (this.mAdapterService != null
        && (leAudioService = getLeAudioService()) != null
        && containsUuid(uuids, BluetoothUuid.LE_AUDIO)     // ★ 唯一闸门
        && leAudioService.getConnectionPolicy(device) == -1) {
        setProfilePolicy(device, 22, autoConnectProfilesSupported ? 100 : 0);
    }
}
```

`handleLeAudioAllowedScenario` / `handleLeAudioForbiddenScenario` 里的每一个分支同样
`containsUuid(uuids, BluetoothUuid.LE_AUDIO)`。

还有一条 OPPO 自己的「让位给 LE Audio」通路：

```java
// OplusLeAudioPhonePolicyExt.oplusProcessDeviceConnected
} else if (this.mPhonePolicyExt.disableBrLinkForLea(device, transport)) {
    connectLeAudioSet(device);
}

// OplusPhonePolicyExtImpl.disableBrLinkForLea
int classOfDevice = devClass.getClassOfDevice() & 16384;          // 0x4000
if (mAdapterService == null || getBondState(device) != 12 || classOfDevice != 16384) {
    return false;                                                  // ← AZ100 在这里就 return 了
}
```

AZ100 的 CoD = `0x240404`（major class = 0x04 Audio/Video，minor = 0x01 Wearable Headset），
`0x240404 & 0x4000 == 0` → **不等于 16384 → 直接 return false**，
`connectLeAudioSet()` 永远不会被调用。

> 这一条我标为「**候选闸门，未完全确认**」：`& 0x4000` 对 CoD 来说不是常见的位域
> （Audio/Video 的 major class 是 `0x0400`）。OPPO 在 `OplusHeadsetStateMachineExtImpl`
> 里也用了同一个 `& 16384`，所以更像是 OPPO 自己的约定，而不是笔误。

**小结**：`OplusLeAudioPhonePolicyExt` 的每一条路径、以及 `PhonePolicy#processInitProfilePriorities`
的第一道 AND 条件，前提都是 `uuids` 里含 `LE_AUDIO`。所以只要手机不建 LE 链路，
手机侧做任何策略层改动都是空转。

### 8.6.4 现场装的两个 LSPosed 模块

`/data/adb/lspd/config/modules_config.db`（+ WAL）读出：

| 模块 | 版本 | enabled | 作用域 |
| --- | --- | --- | --- |
| `com.tosasitill.az100` | — | 1 | `com.oplus.melody` |
| `com.flyfish233.coloros.leaudiofx` | 0.6.6（装于 13:13:57，更新于 13:19:08） | 1 | `com.android.bluetooth` |

`modules_2026-10-11T13:20:05.450342.log` 证明 `leaudiofx` 每次都注入成功：

```
(com.android.bluetooth)[com.flyfish233.coloros.leaudiofx] lea-native-filter/core BASS HOOK_INSTALLED identity[identityReadComplete=false]
(com.android.bluetooth)[...] ACTIVATION_HOOK_INSTALLED
(com.android.bluetooth)[...] ADM_NULL_GUARD_HOOK_INSTALLED scope=A2DP deopt_requested=2
(com.android.bluetooth)[...] JAVA_ARM_J WAITING_VENDOR_DEX
(com.android.bluetooth)[...] armed bass=true activation=true javaArmJ=true activeDevice=true
(com.android.bluetooth)[...] JAVA_ARM_J HOOK_INSTALLED
(com.android.bluetooth)[...] JAVA_ARM_VCP_INIT HOOK_INSTALLED
(com.android.bluetooth)[...] installed bass=true activation=true javaArmJ=true activeDevice=true
```

反编译 `base.apk`（`assets/xposed_init` → `com.flyfish233.coloros.leaudiofx.HookEntry`）
得到的 hook 目标：

| 常量 | 值 |
| --- | --- |
| `TARGET_CLASS` | `com.oplus.bluetooth.leaudio.OplusLeAudioPhonePolicyExt` |
| `TARGET_METHOD` | `initLeAudioPriority`（3 参数） |
| `VOLUME_CONTROL_TARGET_METHOD` | `initVolumeControlPriority` |
| `VENDOR_EXTENSION_PATH` | `/system_ext/framework/oplus_bluetooth_common_ext.jar` |
| `BYPASS_ALLOW_LIST_PROPERTY` | `persist.bluetooth.leaudio.bypass_allow_list` |

`OplusLeAudioPhonePolicyExt` 确认存在于该 jar（`strings` 命中
`Lcom/oplus/bluetooth/leaudio/OplusLeAudioPhonePolicyExt;` + `initLeAudioPriority`）。

**但这个模块对 AZ100 无效** —— 它 hook 的正是上面 §8.6.3 那个方法，
而那个方法的**方法体守卫** `containsUuid(uuids, LE_AUDIO)` 是 false，
即使 hook 生效也只能改「策略值」，改不出 `LeAudioProfile`、更建不出 LE ACL。

另外该模块 `DEBUG_EVENTS = false` 且 `logEvaluation()` 是空方法，**运行时零日志**，
所以看不到它的判定结果（只有安装日志）。

### 8.6.5 当前判定：唯一缺失的环节

```
耳机广播 0x184E            ✅（13:20 起）
手机 LE 扫描收到           ✅
手机建 LE ACL              ❌ ← 缺这一环
  ↓ 因此
LE 侧服务发现 / mUuidsLe   ❌
getUuids() 含 0000184e     ❌
LeAudioProfile 进 mProfiles❌
设置里的 LE Audio 开关      ❌
OplusLeAudioPhonePolicyExt ❌（守卫不过，整类空转）
```

**结论：必须让手机对 AZ100 走 LE 而不是 BR/EDR。** 可选路线见 §10。

---

## 9. 附：本次新增的 DEX 小工具

为了在 200MB 的 Settings.apk 里定位「哪个方法引用了某个字符串」，新增了
`/tmp/settings/strref2.py`（`/tmp/melody/strref.py` 的改进版）：

```bash
python3 strref2.py "persist.bluetooth.leaudio.toggle_visible" "dex/classes*.dex"
# ### classes5.dex string idx [61009]
#    -> Lcom/android/settings/bluetooth/BluetoothDetailsProfilesController; # updateLeAudioConfig
```

以及 `/tmp/melody/methodref.py <方法名> <dex...>`：扫描 `invoke-kind`（0x6e–0x78）
反查「谁调用了某个方法」。本次就是靠它确认 `getUuidsFromLeAdvertisingData()`
只被 `prepareDiscoveryResultIntent` 使用。

改进点：先一次性建好「方法体偏移区间索引」（按起始偏移排序 + 二分），
避免原版对每个候选指令都全量扫一遍 class_def（200MB 级别会慢到不可用）。

踩过的坑（承接上一版）：

1. `encoded_method` 的 `method_idx_diff` 在 **direct / virtual 两组之间各自从 0 重新累加**；
2. 解析 `class_data_item` 时**不能丢掉 static/instance 字段计数**，否则后面的方法全错位；
3. `const-string vAA, string@BBBB` 的 opcode 在索引前 **2** 字节（中间隔着寄存器字节）。
   → 注意 macOS 自带 BSD `grep` **不支持 BRE 的 `\|` 交替**，写多模式匹配一律加 `-E`，
   否则会得到静默的空结果。

---

## 10. 下一步可选路线（按性价比排序）

前提：**必须让手机对 AZ100 建立 LE ACL**（§8.6.5 里唯一缺的那环）。
以下三条互不冲突，可以逐条试。

### 路线 A：让耳机变成「LE-only」广播（最干净，但取决于耳机固件）

OPPO 的原生日志里已经明说了：

```
[oplus_btm_ble_gap.cc:458] NOT_BR_EDR support bit not set, treat device as DUMO
```

AZ100 的 LE 广播**没有置 `BR/EDR Not Supported` 标志位**，所以手机把它当双模设备，
`createBond()` 的 `transport=0`(AUTO) 就选了 BR/EDR。

- 如果 AZ100 固件里有「LE Audio only」模式，切过去后广播会置该标志位，
  手机就会走 LE 配对 → LE 服务发现 → `mUuidsLe` 拿到 `0000184e` → 全链路打通。
- 目前 Technics App 只暴露了 **Prioritize LE Audio**（不是 only），所以这条路
  需要确认固件是否支持。**先试把固件升到 App 提示的 1.040**（`keyLatestFwVersionAz100 = 10.40`，
  下载地址 `https://appsdmfdl.svrpf.jp/dl/EAH-AZ100/BT_00104000/EAH-AZ100_SW1.040_market_release.bin`）。

### 路线 B：强制 LE 配对（最小改动，可控）

`BluetoothDevice#createBond(int transport)` 是 hidden API，`transport=2` 即 LE。
在 LSPosed 里 hook `AdapterService#createBond`（或 `BluetoothDevice#createBond`）
把 `transport` 改成 2，再重新配对一次：

```java
// 伪代码
XposedHelpers.findAndHookMethod("com.android.bluetooth.btservice.AdapterService",
        cl, "createBond", BluetoothDevice.class, int.class, OobData.class, OobData.class, String.class,
        new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                if (isTargetDevice((BluetoothDevice) p.args[0])) p.args[1] = 2;  // TRANSPORT_LE
            }
        });
```

配对后立即验证 `[LE UUIDs    ]` 是否出现。**这是目前最有针对性的一次实验。**
（注意：这比「伪造 `0000184e`」安全得多 —— 它让手机真的去建 LE 链路，而不是骗 UI。）

### 路线 C：继续等/换第三方模块

`com.flyfish233.coloros.leaudiofx` 只 hook 了 `initLeAudioPriority` / `initVolumeControlPriority`，
在「手机不建 LE 链路」这个场景下必然空转。可以：
- 找作者确认是否支持双模耳机（这个场景需要先强制 LE 配对）；
- 或把它的 `DEBUG_EVENTS` 逻辑（`logEvaluation()` 目前是空方法）打开，看判定细节。

### 不建议

> ⚠️ **本节原有判断已被实测推翻，见 §12。** 原文写的是
> 「hook 蓝牙进程伪造 `0000184e` —— 只会让开关出现，耳机并没有变成 LE Audio，
> 打开开关反而可能把音频搞坏」。实测结果相反：AZ100 的 GATT 数据库里确实
> 有完整的 ASCS/PACS/BASS，厂商只是把 LEA UUID 从广播数据里过滤掉了；
> 把这个 UUID 补回去之后建立的是**真实的 LE Audio 单播链路**，ASE 能走到
> `STREAMING (0x04)`，不是空壳开关。保留原文以记录这次纠错。

同理，`persist.sys.oplus.bt.le_audio_white_list_enable=false`
在当前证据下也**不是**有效杠杆（白名单根本不是这条链上的闸门，闸门是 UUID）。

---

## 11. 本次新增的排查命令

| 目的 | 命令 |
| --- | --- |
| 原生日志在哪 | `adb shell su -c "ls -lt /data/misc/bluetooth/logs/"` |
| 统计某标志位在历史日志里的出现次数 | `adb shell su -c 'grep -c "0x184E" /data/misc/bluetooth/logs/bluetooth_*.log'` |
| 把 9MB 原生日志拉到本地再 grep（别在设备上 grep，会超时） | `adb shell su -c "cat <log>" > /tmp/btlog/bt.log` |
| LSPosed 模块是否注入 | `adb shell su -c "ls -lt /data/adb/lspd/log/"` 然后看 `modules_*.log` |
| LSPosed 模块作用域/开关 | 拉 `/data/adb/lspd/config/modules_config.db`(+`-wal`) 后用 `sqlite3` 读 `modules_state` / `scope` |
| 厂商 LE Audio 策略类 | `adb shell su -c "cat /system_ext/framework/oplus_bluetooth_common_ext.jar"` 再 jadx |
| 白名单相关属性 | `adb shell su -c "getprop \| grep -iE 'le_audio\|leaudio'"` |

---

## 12. 路线 B 结果（实测成功）：`0x184E` 进 `mUuidsLe`，整条链就通了

**日期**：2026-10-11 15:12–15:15
**模块**：`com.tosasitill.az100` **v1.5.1**（versionCode 10），开关 `SPLICE_LE_AUDIO_UUIDS = true`
**设备**：OnePlus PLJ110 / ColorOS（Android 16），蓝牙进程 pid 9925

本节结论取代 §8.6.5 的「唯一缺失环节」判定，并推翻 §10「不建议」里的旧假设。

### 12.1 最终闸门只有一个：`arrayContains(uuids, BluetoothUuid.LE_AUDIO)`

`PhonePolicy.smali:2338–2386`（`processInitProfilePriorities` 内的判定）：

```java
if (leAudioService.isPresent()
    && Util.arrayContains(uuids, BluetoothUuid.LE_AUDIO)      // ★ 必须是 0x184E
    && leAudioService.getConnectionPolicy(device) != 0
    && (mLeAudioEnabledByDefault || Utils.isDualModeAudioEnabled())
    && (isBypassLeAudioAllowlist || shouldEnableHapByDefault
        || isLeAudioAllowed(device) || isLeAudioOnlyDevice(device))) {
  allowed = true;
}
```

### 12.2 厂商的 allow-list 早就已经放行了

这台机器上两个「旁路」属性**本来就是开的**，实测：

| 属性 | 值 |
| --- | --- |
| `persist.bluetooth.leaudio.bypass_allow_list` | `true` |
| `persist.bluetooth.enable_dual_mode_audio` | `true` |
| `persist.bluetooth.leaudio.allow_list_extend` | （空） |
| `persist.sys.oplus.bt.le_audio` | `true` |

所以 §12.1 的五个合取项里，**其余四项全部成立**，唯一为假的就是 `arrayContains(uuids, LE_AUDIO)`。

顺带查清了厂商留的三个 allow-list 杠杆（本次都**没有**用到，留档备查）：

- `AdapterService#cacheLeAudioAllowlistDevicesFromProp()` 把
  `BluetoothProperties.le_audio_allow_list()`（sysprop 列表）和
  `persist.bluetooth.leaudio.allow_list_extend`（逗号分隔，**可写**）合并进
  `mLeAudioAllowDevices`；
- `AdapterService#isLeAudioAllowed(device)`（`AdapterService.smali:14103`）
  是**按型号名**匹配的：`mLeAudioAllowDevices.contains(props.getModelName())`，
  不是按地址；
- 所以理论上 `setprop persist.bluetooth.leaudio.allow_list_extend "Technics EAH-AZ100"`
  也能让 `isLeAudioAllowed` 变真 —— 但因为它已经是 OR 项里的冗余条件，
  对这条链没有增量作用。

### 12.3 修的位置：`RemoteDevices$DeviceProperties#setUuidsLe` 的入参

写入链（`RemoteDevices.smali`）：

```
AdapterNativeCallback#devicePropertyChangedCallback([BI[[B)
  └─ RemoteDevices#devicePropertyChangedCallback([BI[[B)   ← 方法体 3333–4186
       switch(type) case 0x1c  (BT_PROPERTY_UUIDS_LE, 见 AbstractionLayer.smali:109)
         v2 = Utils.byteArrayToUuid(values[i])
         if (!areUuidsEqual(v2, getUuidsLe())) 
           DeviceProperties#setUuidsLe(v2)                  ← 唯一的写入点（第 3995 行）
             mUuidsLe = v2
         → v11 = 1
  末尾: if (v11) triggerUuidNotification(device)
```

读出口有两处，**都读同一个字段**：

- `DeviceProperties#toString()` 第 967–969 行 → `dumpsys bluetooth_manager` 的 `[LE UUIDs]`；
- `DeviceProperties#getUuids()` 第 498 行 → 合并 `mUuidsBrEdr + mUuidsLe`，
  即 `BluetoothDevice.getUuids()`、`ACTION_UUID` 广播、以及 Settings 侧
  `CachedBluetoothDevice#updateProfiles()` 读到的值。

**因此模块改的是写入路径**（`LeAudioTransportHook#spliceIntoLeUuids`），
把 `setUuidsLe` 的实参 `args[0]` 换成「原数组 + `0000184e`」，只对 `matches(address)` 的地址生效。

> 早期版本挂的是 `getUuids()` 的返回值，并且以
> `mUuidsFromLeAdvertisingData` 里已含 `0x184E` 为前提 —— 而厂商恰恰把
> LEA UUID 从广播数据里剥掉了（`removal of lea uuids`），那个前提永远不成立，
> 属于死代码。而且它改不到 `dumpsys` 的 `[LE UUIDs]`（读原始字段，不走 getter）。
> 两处都已改掉。

### 12.4 实测证据

**（a）UUID 补上了**（`dumpsys bluetooth_manager`）：

```
Bonded devices: 1
  XX:XX:XX:XX:84:85(Public ) => XX:XX:XX:XX:84:85(Public ) [ DUAL ] [0x240404]
      [Pairing Algorithm BR/EDR: 3 LE: 3] [ACL BR/EDR:Y LE:N] Technics EAH-AZ100
      [BR/EDR UUIDs]: 00000000-… 00001108-… 0000110b-… 0000110e-… 0000111e-… 0000ff01-… 32bf2fe6-… 5052494d-…
      [LE UUIDs    ]: 0000180f-0000-1000-8000-00805f9b34fb 0000184e-0000-1000-8000-00805f9b34fb   ← 新增
```

模块钩子日志（`/data/misc/bluetooth/az100.log`）：

```
routeB: setUuidsLe splice armed
routeB: spliced 0000184e into mUuidsLe (1 -> 2) **:**:**84:85
routeB: setUuidsLe
    mUuidsLe = 2 [0000180f-… 0000184e-…]
```

**（b）策略闸门打开**：

```
XX:XX:XX:XX:84:85: LE_AUDIO policy changed: -1 -> 0
XX:XX:XX:XX:84:85: LE_AUDIO policy changed: 0 -> 100
le_audio: ALLOWED
```

**（c）真实的 LE Audio 单播链路建立**：

```
LE_AUDIO connection state changed: XX:XX:XX:XX:84:85 STATE_DISCONNECTED -> STATE_CONNECTING
LE_AUDIO connection state changed: XX:XX:XX:XX:84:85 STATE_CONNECTING  -> STATE_CONNECTED
currentlyActiveGroupId: 1
group lead: XX:XX:XX:XX:84:85
StateMachine: name=LeAudioStateMachine state=Connected
LeAudioGroupDescriptor.setActiveState (groupId: 1): inactive -> active
mLeAudioActiveDevice: XX:XX:XX:XX:84:85
mLeAudioConnectedDeviceNum: 1
Group sink supported contexts: 0x0fff [UNSPEC|CONV|MEDIA|GAME|INSTR|VOICEA|LIVE|SFX|NOTIF|RING|ALERTS|EMERG|…]
```

**（d）ASE 走到 STREAMING（这是「音频真的在 LE Audio 上跑」的铁证）**：

```
15:14:41.981 ASCS_CP_CMD  GID 1  CODEC_CONFIG: ASE_ID 1,  snk,6,3;;      ← LC3 配置
15:14:42.011 ASE_NOTIF    GID 1  ASE_ID 1: CODEC_CONFIGURED (0x01)
15:14:42.040 ASE_NOTIF    GID 1  ASE_ID 1: QOS_CONFIGURED (0x02)
15:14:42.040 ASCS_CP_CMD  GID 1  ENABLE: ASE_ID 1,  meta: 03020400020501;;
15:14:42.087 ASE_NOTIF    GID 1  ASE_ID 1: ENABLING (0x03)
15:14:42.490 ASE_NOTIF    GID 1  ASE_ID 1: STREAMING (0x04)              ← 推流中
```

（之后每 ~15 s 一次 `RELEASE → CODEC_CONFIGURED → QOS_CONFIGURED → ENABLING → STREAMING`
循环，是没有媒体播放时的正常 sink 挂起/恢复。）

**（e）Settings 进程自己也认了**（`pid 6090 = com.android.settings`）：

```
D/CachedBluetoothDevice( 6090): onProfileStateChanged: profile LE_AUDIO, device XX:XX:XX:XX:84:85, newProfileState 2
D/CachedBluetoothDevice( 6090): onActiveDeviceChanged: profile LE_AUDIO, device XX:XX:XX:XX:84:85, isActive true
```

**（f）底层 `connection_manager` 也看到了**：

```
D/Bluetooth [connection_manager.cc:199] IsTargetedAnnouncement: Found UUID 0x184e
```

### 12.5 为什么开关现在一定可见（代码级推导）

`newProfileState 2` 这一条就足够，因为 settingslib 的
`CachedBluetoothDevice#onProfileStateChanged` 在**连接态**下会把 profile 无条件塞进 `mProfiles`：

```java
// /tmp/sl/.../CachedBluetoothDevice.java:1367–1377
if (i == 2) {                                       // STATE_CONNECTED
    if (!this.mProfiles.contains(localBluetoothProfile)) {
        this.mRemovedProfiles.remove(localBluetoothProfile);
        this.mProfiles.add(localBluetoothProfile);   // ← LeAudioProfile 进 mProfiles
    }
}
```

即 **不再依赖 `updateProfiles()` / `getUuids()`**（那条路 §2.2 已分析）。
`getUiAccessibleProfiles()` 遍历 `mProfiles`，而 `LeAudioProfile#accessProfileEnabled()` 恒为 true。

可见性判定（`BluetoothDetailsProfilesController.java:290–296`）：

```java
if (localBluetoothProfile instanceof LeAudioProfile) {
    boolean z2 = this.mIsLeAudioToggleEnabled;
    if (this.mIsLeAudioOnlyDevice) z2 = false;
    twoStatePreference.setVisible(z2);
}
```

- `mIsLeAudioToggleEnabled` 由 `updateLeAudioConfig()` 决定：
  `persist.bluetooth.leaudio.toggle_visible` 与
  `ro.bluetooth.leaudio.le_audio_connection_by_default` **两个属性在设备上都未设置**
  → 均取默认 `true` → `mIsLeAudioToggleEnabled = true`；
- `mIsLeAudioOnlyDevice = (!zContains || z || zContains2) ? false : true`，
  其中 `z = 含 A2dp || 含 Headset`。AZ100 的 `[BR/EDR UUIDs]` 里有
  `00001108`(Headset) 与 `0000110b`(A2DP) → `z = true` → `mIsLeAudioOnlyDevice = false`。

→ `setVisible(true)`，开关出现。

### 12.6 遗留的厂商噪音（已无害）

`isLEAudioDeviceInWhiteList: LE Audio device is not in Whitelist` 仍会打印
（`oplus_le_impl.h:1100`），但已经不再阻断任何东西 —— 它只影响厂商自己的
「连接模式/长距模式」等附加优化，不影响 LE Audio 建链。

### 12.7 现场遗留的系统改动（可回退）

本次实验过程中写入过一个 `Settings.Global` 键：

```bash
settings get global ble_audio_address
# B8:20:8E:EE:84:85;B8:20:8E:EE:84:85;B8:20:8E:EE:84:85;
```

**实测对结果没有影响**（`ble_audio_address` 属于 `OplusLeAdapterService`
的 DUMO 白名单，不是 §12.1 那条链上的闸门）。若要保持系统原状，可执行：

```bash
adb shell su -c "settings delete global ble_audio_address"
```

`persist.bluetooth.leaudio.allow_list_extend` **未**被修改（仍为空）。


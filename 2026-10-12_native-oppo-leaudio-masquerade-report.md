# 伪装成原生 OPPO 耳机走原生 LE Audio 路线 —— 逆向结论与落地方案

> 这是 2026-10-12 的排查记录。文件后面曾经建议关掉 `SPLICE_LE_AUDIO_UUIDS`，实机证明那样会让系统设置开关消失，已经改回。
> 地址键白名单没有打开 native 门；当前生效的是进程内补丁、UUID 注入和 `connectLeAudio()` 直连。现状见 `README.md`。

> 2026-10-12。目标：搞清楚 ColorOS **原生**对「OPPO 自家耳机」的 LE Audio 处理链路，
> 然后让 AZ100 伪装成原生耳机，用原生路径替代当前模块的一堆 hook（"崩溃少点"）。
>
> 全程只读设备（未做任何蓝牙修改性操作）。

---

## 0. 一句话结论

原生 OPPO 耳机的 LE Audio 识别**不是靠地址表**，而是靠两个键：

| 键 | 载体 | 匹配方式 | 谁在用 |
| --- | --- | --- | --- |
| **地址键** | native interop 数据库（持久化到 `/data/misc/bluedroid/bt_iot_update_list.conf`） | `OplusLeAudioWhiteList` 段 + 完整 6 字节地址 | **Java 侧**：`OplusLeAudioRemoteDevice#handleOplusDevicePropertyChangedUuids`（决定要不要剥 UUID） |
| **厂商/产品键** | `bt_config.conf` 的 `broadcastVendorId` + `broadcastProductId` | 与 libbluetooth_jni.so 里 **8 条硬编码**的 OPPO 产品 ID 比对 | **native 侧**：EIR 处理、LEA bit、encrypt retry、LE 连接完成事件 |

**AZ100 两个键都没有 → 被当成"非 OPPO 的 LE Audio 设备" → 被剥掉 `184E/BATTERY/CSIS/VCS`。**

**伪装 = 把这两个键补上。** 地址键可以运行时零 hook 写入；厂商/产品键需要改一次 `bt_config.conf`。

---

## 1. 原生链路全貌

### 1.1 发现阶段（native，EIR 解析）

```
oplus_btm_process_inq_results(RawAddress, array<u8,3> CoD, const u8* eir, int len, ADV_FEATURE)
oplus_btm_ble_process_adv_pkt_cont(RawAddress, vector<u8>, ADV_FEATURE)
    └─ get_vendor_product_id_and_lea_bit_from_eir_data(eir, len, uint16_t& vendor, uint32_t& product, int& lea_bit)
    │      └─ 需要 EIR 厂商数据以 company id 0x079A 开头（OPPO 的 SIG company id）
    ├─ btif_config_set_int(addr, "broadcastVendorId",  vendor)    ← 写回 bt_config.conf
    ├─ btif_config_set_int(addr, "broadcastProductId", product)   ← 写回 bt_config.conf
    ├─ soc_btif_dm_check_dumo_manufacturer_data(...) → Vendor.onDumoGroupInfo(br, le1, le2)
    │      → OplusAdapterServiceExtImpl.setDumoGroupInfo(...) → OplusLeAdapterService.setDumoGroupInfo(...)
    │      → mCachedBRAddress / mCachedLE1Address / mCachedLE2Address / mLeAudioAddressMap
    └─ interop_match_vendor_and_3bytes_product_id("OplusLeAudioWhiteList", vendor, product)
```

### 1.2 白名单匹配（native）

`interop_match_by_feature_name_impl(const char* feature, const RawAddress* addr)` @ `libbluetooth_jni.so+0x1112e40`：

```asm
1112e70: adrp x1, 0x6d000
1112e74: add  x1, x1, #0x7b3        ; x1 = "OplusLeAudioWhiteList"
1112e80: bl   strcmp@plt
1112e84: cbz  w0, 0x1112fc0         ; == 0 -> 特殊分支
```

特殊分支（反汇编 0x1112fc0..0x1113284）：

```c
// 伪码
string addrStr = addr->ToString();
int vendor, product;
if (!btif_config_get_int(addrStr, "broadcastVendorId",  &vendor))  return false;
if (!btif_config_get_int(addrStr, "broadcastProductId", &product)) return false;
return interop_match_vendor_and_3bytes_product_id(feature, vendor, product);
```

普通分支则走 `interop_matcher(feature).match(*addr, {option})` —— **纯按地址**。

> 关键：`interop_match_by_feature_name_impl` 的**唯一调用者**是
> `stack_connection_complete_event_report`（LE 连接完成事件），另有 `oplus_btif_is_lea_device`、
> `oplus_bta_dm_check_lea_whitelist`、`oplus_btm_sec_retry_le_encrypt_if_needed`、
> `oplus_btm_sec_encrypt_change`、`oplus_btm_process_inq_results`、
> `oplus_btm_ble_process_adv_pkt_cont`、`oplus_handle_le_audio_lea_bit_processing` 等
> 共 9 处直接调用 `interop_match_vendor_and_3bytes_product_id`。

### 1.3 硬编码的 OPPO 产品表

`libbluetooth_jni.so` 的 `.rodata` 偏移 `0x1cd66c` 起，**8 条、步长 0x44(68) 字节**：

```c
struct { uint32_t company_id;   // 恒为 0x079A（OPPO SIG company id）
         uint32_t product_id;   // 3 字节，如 0x062014
         char     feature[60];  // "OplusLeAudioWhiteList"
       };                        // sizeof == 68
```

| # | product_id |
| --- | --- |
| 1 | `0x062014` |
| 2 | `0x063014` |
| 3 | `0x064014` |
| 4 | `0x066010` |
| 5 | `0x067410` |
| 6 | `0x06E010` |
| 7 | `0x06EC10` |
| 8 | `0x067814` |

由 `config_from_oplus_interop()` 遍历注册（反汇编 0x110ff78 起）：

```asm
110ff78: adrp x20, 0x1cd000
110ff7c: add  x20, x20, #0x674      ; &table[0].feature
110ff84: mov  w24, #0x8             ; 8 条
110ffa0: add  x20, x20, #0x44       ; 步长 68
110ffac: ldur w4, [x20, #-0x4]      ; product_id
110ffb8: mov  w3, #0x79a            ; company_id 硬编码
1112ac0: sprintf(buf, "0X%04X-0X%06X", w3, w4)   ; 0x121049 = "0X%04X-0X%06X"
```

→ 注册成 `interop_combination_entry`，键形如 `0X079A-0X062014`。

### 1.4 Java 侧：UUID 剥离（本方案的直接目标）

`com.oplus.bluetooth.leaudio.OplusLeAudioRemoteDevice#handleOplusDevicePropertyChangedUuids`：

```java
if (mAdapterService != null && !isLeAudioDevice(bdDevice.getAddress())) return newUuids;
boolean deviceInLeaWhiteList = nativeChannel.interopDatabaseMatch("OplusLeAudioWhiteList", bdDevice);
if (arrayContains(newUuids, LE_AUDIO) && !deviceInLeaWhiteList) {
    // 删掉 184E(LE_AUDIO) / 180F(BATTERY) / 1846(CSIS) / 1844(VCS)
}
return newUuids;
```

调用点：`OplusRemoteDevicesExtImpl#oplusHandleDevicePropertyChangedCallback`，
`type == 3`(BR/EDR UUIDs) 或 `type == 28`(LE UUIDs) 都会先过这一层，
结果写入 `deviceProperties.setUuidsBrEdr(...)` / `setUuidsLe(...)`。

**这就是当前模块的 `SPLICE_LE_AUDIO_UUIDS` 被打回的原因**：
模块把 `184E` 塞进 LE UUID 列表 → `isLeAudioDevice()` 变 true → 进入剥离分支 → `184E` 又被删掉。

### 1.5 读写对称性（本方案成立的关键证明）

JNI 层（`libbluetooth_jni.so`）：

```
interopDatabaseAddByUplayerNative  @0x4ba530:
    x8 = [0x11ad000 + 0x460]          ; 全局 interop 模块对象
    ... blr [x8 + 0x30]               ; -> interop_database_add_by_addr_impl(feature, addr, len)

interopDatabaseMatchNative         @0x4ba060:
    x8 = [0x11ad000 + 0x460]          ; 同一个全局对象
    ... blr [x8 + 0x20]               ; -> interop_oplus_match_addr(feature, addr)
```

`interop_oplus_match_addr(const char*, const RawAddress*)` @ `0x10f4620` 反汇编：

```asm
10f467c: bl 0x1100f80   ; interop_matcher::interop_matcher(string_view feature)
10f46d8: bl 0x1102720   ; interop_matcher::match(const RawAddress&, vector<match_option>)
```

**没有 `OplusLeAudioWhiteList` 特殊分支 → 纯按地址查表。**

> 结论：`interopDatabaseAdd(device, "OplusLeAudioWhiteList", 6)` 写入的，
> 正是 `interopDatabaseMatch("OplusLeAudioWhiteList", device)` 读取的那张表。**读写对称。**

### 1.6 已确认的既有事实

- 设备上 `/data/misc/bluedroid/bt_iot_update_list.conf` **已经有 AZ100 的条目**：
  ```
  [OplusDelayConnectProfile1S]
  AddressBlacklist = b8:20:8e:ee:84:85
  ```
  来源是 `OplusPhonePolicyExtImpl#modifyReduceConnTimeoutList`（A2DP+HFP 同时连上时调用
  `interopUtil.interopDatabaseAdd(device, "OplusDelayConnectProfile1S", 6)`）。
  → **证明这套 API 对 AZ100 有效，并且会持久化。**
- 设备上**任何 conf 里都没有 `OplusLeAudioWhiteList` 段**（`/etc/bluetooth/bt_interop_iot_list.conf`
  / `/system/etc/...` / `bt_iot_update_list.conf` / `interop_database_dynamic.conf` 全查过）。
- `bt_config.conf` **12 个设备段里没有任何一个带 `broadcastVendorId`**。
- `Settings.Global["ble_audio_address"]` = `B8:20:8E:EE:84:85;B8:20:8E:EE:84:85;B8:20:8E:EE:84:85;`
  → native 已经为 AZ100 报过 dumo 三段地址。
- `Settings.Global` 里**没有** `B8:20:8E:EE:84:85 = ON`，`/data/data/com.android.bluetooth/shared_prefs/`
  里**没有** `le_aduio_status.xml` → **LE Audio 开关从未被打开过**。
- `bt_config.conf` AZ100 段：`DevType = 3`、`DevClass = 2360324`（CoD bit14 **未置位**）、
  `LeAudioAutoconnect = 0`、**无 `ServiceLe`**。
- `persist.bluetooth.enable_dual_mode_audio` = `true`
- `persist.bluetooth.leaudio.bypass_allow_list` = `true`
- `persist.sys.oplus.bt.le_audio_white_list_enable` = `true`

### 1.7 栈里的 LE Audio 闸门（旁证）

`PhonePolicy#processInitProfilePriorities`：

```java
boolean z  = SystemProperties.getBoolean("persist.bluetooth.leaudio.bypass_allow_list", false); // true
boolean z2 = leAudioService.isPresent()
          && arrayContains(uuids, BluetoothUuid.LE_AUDIO)                       // ← 缺 184E
          && leAudioService.getConnectionPolicy(device) != 0                    // ← 需 policy=100
          && (mLeAudioEnabledByDefault || Utils.isDualModeAudioEnabled())       // true
          && (z || shouldEnableHapByDefault || isLeAudioAllowed(device) || isLeAudioOnlyDevice); // z=true
```

`AdapterService#isLeAudioAllowed` = `mLeAudioAllowDevices.contains(deviceProperties.getModelName())`，
而 `mLeAudioAllowDevices` 来自 `cacheLeAudioAllowlistDevicesFromProp()`：

```java
static final String LE_AUDIO_ALLOW_LIST_EXTEND = "persist.bluetooth.leaudio.allow_list_extend";
// 逗号分隔的机型名，追加进放行名单
```

→ 这是另一条**零 hook 的机型名放行通道**（本机 `bypass_allow_list` 已 true，故非必需，但可作为兜底）。

---

## 2. 伪装方案

### 方案 A（推荐）：补两个键

**A1. 地址键 —— 运行时写入，零 hook、不需重启蓝牙**

调用 OPPO 自己的公开 API：

```java
IOplusBluetoothInteropUtil util = OplusBtInteropUtil.getInstance();
util.interopDatabaseAdd(device, "OplusLeAudioWhiteList", 6);   // 6 = 完整地址长度
```

会持久化成：

```
[OplusLeAudioWhiteList]
AddressBlacklist = b8:20:8e:ee:84:85
```

（`AddressBlacklist` 是历史命名，语义是"命中名单"。）

**A2. 厂商/产品键 —— 改一次 `bt_config.conf`**

在 AZ100 的段里加两行：

```ini
[b8:20:8e:ee:84:85]
...
broadcastVendorId = 1946       # 0x079A
broadcastProductId = 401428    # 0x062014
```

（选 `0x062014` 即可，8 个 ID 任选其一。）

> ⚠️ **风险提示**：`oplus_btm_process_inq_results` / `oplus_btm_ble_process_adv_pkt_cont` 在解析 EIR 时
> 会 `btif_config_set_int(addr, "broadcastProductId", ...)` **无条件写回**（反汇编 0x76c180 处）。
> 如果 native 对 AZ100 的 EIR 解析路径真的跑到了那一句，伪造值可能被覆盖。
> 因此 A2 属于"锦上添花"，**A1 才是必须的、且不会被覆盖的**（native 不会去改 `bt_iot_update_list.conf`
> 里我们自己加的段）。

### 方案 B（兜底）：机型名放行

```bash
su -c 'setprop persist.bluetooth.leaudio.allow_list_extend "Technics EAH-AZ100"'
```

只影响 `isLeAudioAllowed()`；本机 `bypass_allow_list` 已 true，所以是冗余保险。

### 方案 C（不推荐）：伪造 EIR

让 AZ100 的 EIR 里出现 company id `0x079A` + 上述产品 ID。耳机侧改不了，
手机侧要改 native EIR，属于最深度的 hook，与"崩溃少点"的目标相反。**排除。**

### 明确不要做的

- **不要**继续往 `interopDatabaseAdd` 之外的路径堆 hook（`handleOplusDevicePropertyChangedUuids`
  直接放行虽然只要 1 个 hook，但那是"绕开"而不是"伪装"，原生其余 9 处
  `interop_match_vendor_and_3bytes_product_id` 依然为 false，LEA bit / encrypt retry / dumo 全都不会走）。

---

## 3. 落地步骤

### 第 1 步：一次性手工验证（不需要动模块代码）

```bash
# 1) 备份
adb shell "su -c 'cp /data/misc/bluedroid/bt_config.conf /data/misc/bluedroid/bt_config.conf.bak'"
adb shell "su -c 'cp /data/misc/bluedroid/bt_iot_update_list.conf /data/misc/bluedroid/bt_iot_update_list.conf.bak'"

# 2) 补地址键
adb shell "su -c 'printf \"\n[OplusLeAudioWhiteList]\nAddressBlacklist = b8:20:8e:ee:84:85\n\" >> /data/misc/bluedroid/bt_iot_update_list.conf'"

# 3) 补厂商/产品键（在 AZ100 段内插入，注意行尾）
adb shell "su -c 'sed -i \"/^\[b8:20:8e:ee:84:85\]/a broadcastVendorId = 1946\\nbroadcastProductId = 401428\" /data/misc/bluedroid/bt_config.conf'"

# 4) 兜底：机型名放行
adb shell "su -c 'setprop persist.bluetooth.leaudio.allow_list_extend \"Technics EAH-AZ100\"'"
```

然后**重启蓝牙**（`adb shell su -c 'svc bluetooth disable && svc bluetooth enable'`）或重启手机。

### 第 2 步：验证

```bash
# 应该看到 deviceInLeaWhiteList true
adb shell "su -c 'logcat -d -s BluetoothRemoteDevices:V OplusLeAudioRemoteDevice:V'" | grep -i "deviceInLeaWhiteList\|OplusLeAudioWhiteList"

# UUID 列表里应该稳定出现 0000184e，且不再被剥
adb shell "su -c 'dumpsys bluetooth_manager'" | grep -i "184e\|mUuidsLe\|LeAudio"

# LEA 状态
adb shell "su -c 'settings get global B8:20:8E:EE:84:85'"     # 期望 ON
```

### 第 3 步：把 A1 做进模块（去掉手工依赖）

新增 `NativeLeaWhitelistHook`：在 `com.android.bluetooth` 进程里，通过
`OplusBtInteropUtil.getInstance().interopDatabaseAdd(device, "OplusLeAudioWhiteList", 6)`
在 AdapterService 初始化后 / 每次 BONDED 时幂等登记。这样**重装模块即生效，不用重启蓝牙**。

### 第 4 步：验证通过后，移除 `SPLICE_LE_AUDIO_UUIDS`

一旦 `interopDatabaseMatch("OplusLeAudioWhiteList", AZ100)` 稳定为 true，
OPPO 就不再剥 `184E`；此时模块的 UUID 拼接变成**多余且冲突**的（它会主动把
`isLeAudioDevice()` 拉成 true，反而让剥离分支更容易被触发）。
移除后 BT 进程里的 hook 数量显著下降 —— 正是"崩溃少点"想要的。

> 注意：`184E` 最终能不能进 `mUuidsLe`，还取决于**手机是否建立 LE ACL 并做 LE 侧服务发现**。
> 目前 `bt_config.conf` 无 `ServiceLe`、`mUuidsLe` 为空，说明从来没有 LE ACL。
> 原生的触发点是 `OplusLeAdapterService#checkConnectLeAudioIfNeed`（需要 LEA 开关为 ON）
> 与 Melody 的 BR↔LEA 切换广播 `OPLUS_ACTION_CHANGE_LEA_MODE`。
> 因此第 4 步之前必须先确认 `184E` 有别的来源；否则需要保留 SPLICE 作为"最后一段"。

---

## 4. 关键地址索引（libbluetooth_jni.so）

| 地址 | 符号 |
| --- | --- |
| `0x1cd66c` | 8 条硬编码 OPPO (company 0x079A, product_id) 表 |
| `0x1112e40` | `interop_match_by_feature_name_impl(feature, addr)` |
| `0x1112fc0` | 该函数的 `OplusLeAudioWhiteList` 特殊分支 |
| `0x11128b0` | `interop_match_vendor_and_3bytes_product_id(feature, vendor, product)` |
| `0x10f5830` | `interop_oplus_match_vendor_product_ids(feature, vendor, product)` |
| `0x1101c70` | `interop_matcher::match_vid_pid(vendor_id, product_id)` |
| `0x10f4620` | `interop_oplus_match_addr(feature, addr)` ← JNI `interopDatabaseMatchNative` 的目标 |
| `0x1113580` | `interop_database_add_by_addr_impl(feature, addr, len)` ← JNI `interopDatabaseAddByUplayerNative` 的目标 |
| `0x4ba060` | `interopDatabaseMatchNative` |
| `0x4ba530` | `interopDatabaseAddByUplayerNative` |
| `0x76b9a0` | `get_vendor_product_id_and_lea_bit_from_eir_data` |
| `0x78dfb0` | `soc_btif_dm_check_dumo_manufacturer_data`（dumo 三段地址来源） |
| `0x67b6b0` | `oplus_bta_dm_check_lea_whitelist(addr, bool&)` |
| `0x643508` | `oplus_btif_is_lea_device(addr)` |
| `0x76c338` | `oplus_btm_process_inq_results`（EIR → bt_config 写入） |
| `0x6b0100` | `stack_connection_complete_event_report`（唯一调用 `interop_match_by_feature_name_impl` 的地方） |

（`.rodata` 的 vaddr == 文件偏移；`.text` 同样。符号名来自 `.gnu_debugdata` MiniDebugInfo。）

---

## 5. 复现工具

分析脚本留在 `/tmp`（`findref.py` 查字符串引用、`findbl.py` 查调用点、`syms2.txt` 是解压
`.gnu_debugdata` 后的完整符号表）。要重新生成符号表：

```bash
python3 -c "
import lzma
f=open('libbt_jni.so','rb').read()
open('/tmp/mini.elf','wb').write(lzma.decompress(f[0x11a9062:0x11a9062+560468]))"
llvm-nm -n /tmp/mini.elf > /tmp/syms2.txt
```

---

## 6. 更正与补充（同日第二轮，含设备实测）

第一轮的结论有一处**判据搞反了**，这里更正。

### 6.1 更正：`interop_match_by_feature_name_impl` 的「厂商/产品」分支是死代码

第一轮认为 native 侧靠 `interop_match_by_feature_name_impl(feature, addr)`
（`0x1112e40`）里那个 `strcmp(feature, "OplusLeAudioWhiteList") == 0`
的特殊分支（`0x1112fc0`）来读 `broadcastVendorId`/`broadcastProductId`。

扫描 `.text` 全段后确认：**`0x1112e40` 只有一个调用者**，就是
`stack_connection_complete_event_report` 里的 `0x6b02e0`，而它传的字符串是
**`INTEROP_DCS_REMOTE_VIP_DEVICE`**（`.rodata @ 0x6df05`），不是
`OplusLeAudioWhiteList`。所以 `0x1112fc0` 那个分支**永远不会被执行**。

`0x1112e40` 的实际结构：

```c
if (strcmp(feature, "OplusLeAudioWhiteList") == 0)          // 0x1112e80, @0x6d7b3
    goto vendor_product_branch;                             // 0x1112fc0  —— 不可达
if (strcmp(feature, "INTEROP_DCS_REMOTE_VIP_DEVICE") == 0)  // 0x1112e94, @0x6df05
    goto vip_branch;                                        // 0x1112e9c
goto generic;                                               // 0x1113178
```

VIP 分支做的事（`stack_connection_complete_event_report`，`0x6b02e0` 起）：

```c
if (interop_match_by_feature_name_impl("INTEROP_DCS_REMOTE_VIP_DEVICE", addr)) {
    log(tag="oplus_bt_stack_conn_event", "is_vip = {}");            // fmt @0x5bc5c
    if (find_connection_dev_index(addr) != -1) {                    // 0x6a87e0
        char cur[20]; int n = 20;
        if (!(oplus_iot_config_get_str ("UserIdentity","UserType",cur,&n)   // 0x715650
              && strcmp(cur, "VipUser") == 0)) {
            oplus_iot_config_set_str("UserIdentity","UserType","VipUser",8);// 0x7156c0
            oplus_stack_up_sync_user_type();                        // 0x6a1f90
        }
    }
}
```

即：这是一条**独立的「VIP 设备」标记**功能，跟 LE Audio 白名单无关。
（`"VipUser"` 是 movk 拼出来的，`.rodata` 里搜不到。）

### 6.2 补充：真正的 native 判据是 8 个**直接调用点**

`interop_match_vendor_and_3bytes_product_id("OplusLeAudioWhiteList", vendor, product)`
（`0x11128b0`）在 `.text` 里被直接调用 8 次：

| 调用点 | 所属函数 | 作用 |
| --- | --- | --- |
| `0x6434fc` | `oplus_btif_is_lea_device` (`0x643180`) | **native 侧「是不是 LEA 设备」主闸门** |
| `0x644104` | `oplus_btif_set_remote_deivce_class` (`0x643f40`) | 设置远端 CoD |
| `0x65be80` | `oplus_handle_le_audio_lea_bit_processing` (`0x65ba40`) | 配对确认时的 LEA bit 处理 |
| `0x67b90c` | `oplus_bta_dm_check_lea_whitelist` (`0x67b6b0`) | LEA 白名单检查 |
| `0x76adf0` | `oplus_btm_ble_process_adv_pkt_cont` (`0x76a620`) | LE 广播包处理 |
| `0x76c32c` | `oplus_btm_process_inq_results` (`0x76bcf0`) | 查询结果 / EIR 处理 |
| `0x784e94` | `oplus_btm_sec_retry_le_encrypt_if_needed` (`0x784bb0`) | LE 加密重试 |
| `0x789bdc` | `oplus_btm_sec_encrypt_change` (`0x788950`) | 加密变更 |

其中三个直接读 `btif_config`，**键名就是那两个**：

```
oplus_btif_is_lea_device                  @0x64324c "broadcastVendorId"  @0x643358 "broadcastProductId"
oplus_bta_dm_check_lea_whitelist          @0x67b780 "broadcastVendorId"  @0x67b844 "broadcastProductId"
oplus_handle_le_audio_lea_bit_processing  @0x65bb10 "broadcastVendorId"  @0x65bc28 "broadcastProductId"
```

`oplus_btif_is_lea_device` 另外还引用 `"customLeaBitWritten"`（`@0x643460`）——
LEA bit 写入标记，第三把键（本次未动）。

**结论：方案 A2（手工补这两个键）是必需且充分条件之一，不是可选项。**

### 6.3 更正：Java 侧剥离走的是「按地址」，hook 路径确认正确

`OplusLeAudioRemoteDevice:309` 的实参是 `IOplusBluetoothNativeChannel`，不是
`IOplusBluetoothInteropUtil`：

```java
// OplusLeAudioRemoteDevice.java:308-309
IOplusBluetoothNativeChannel nativeChannel = OplusFeatureCache.get(IOplusBluetoothNativeChannel.DEFAULT);
boolean deviceInLeaWhiteList = nativeChannel.interopDatabaseMatch("OplusLeAudioWhiteList", bdDevice);
```

`OplusBtNativeChannel:126` →

```java
return this.mNativeChannelStub.interopDatabaseMatchNative(
        feature, Util.getBytesFromAddress(device.getAddress()));
```

反汇编确认：

* `interopDatabaseMatchNative` (`0x4ba060`) 用全局对象 `[0x11ad000+0x460]` 的 `+0x20`；
* `interopDatabaseAddByUplayerNative` (`0x4ba530`) 用**同一个** `[0x11ad000+0x460]` 的 `+0x30`；
* `[0x11ad460]` 是在 native channel 初始化（`0x4b9a10`..`0x4b9c30`）里用工厂
  `[0x11ab898]+0x90` + 名字 **`"oplus_bt_native_channel"`**（`@0x599c4`）建出来的 facade；
  另一个对象 `[0x11ad458]` 名字是 **`"interop_oplus"`**（`@0x599b6`），供
  `interopDatabaseMatchByTypeNative` 用（那是 `OplusBtInteropUtil` 那一族的 API）。

**所以 add 和 match 确实落在同一个 facade 上，hook 写进去的地址键能被剥离检查读到。**

`+0x20` 最终落到 `interop_oplus_match_addr` (`0x10f4620`)，它只做：
`strlen(feature)` → 构造 `std::string` → 造一个空的 matcher-options →
`interop_matcher::match` (`0x1102720`)。**全程无 vendor/product、无 `btif_config_get_int`**，纯按地址查表。

### 6.4 设备实测（2026-10-12 14:46）

* 改前 `[b8:20:8e:ee:84:85]` 段无任何 `broadcast*` 键（`grep -c broadcast` = 0）。
* 首次尝试失败：`svc bluetooth disable` 后 `com.android.bluetooth` 进程**并不退出**
  （pid 7224 全程存活），`btif_config` 的内存缓存把文件改回去了 —— 键被覆盖。
* 正确顺序：**停适配器 → 杀进程 → 确认进程消失 → 改文件 → 再启用**。之后键保留：

```
52:broadcastVendorId = 1946
53:broadcastProductId = 401428
```

  新进程 pid 12372，键仍在。

* 落地脚本：`patch-lea-vidpid.sh`（宿主侧，`patch` / `check` / `revert`）+
  `patch-lea-vidpid.device.sh`（设备侧，负责停→杀→改→启）。
* 备份：`/data/misc/bluedroid/bt_config.conf.bak.<时间戳>`（已生成两个）。
* 模块 APK 已更新到设备（`lastUpdateTime=2026-10-12 14:47:16`），
  本次改动：`SPLICE_LE_AUDIO_UUIDS` 由 `true` 改为 `false`，
  `NATIVE_LEA_WHITELIST` 保持 `true`。下次蓝牙进程重启后生效。

**待验证**（本次未做，需要设备重新连接 AZ100）：

```bash
adb logcat -s oplus_bt_btif_dm | grep 'Is oplus LEA device'   # 期望 true
adb logcat -s AZ100:V | grep 'lea-whitelist'                  # 期望 add=true match=true
```

---

## 7. 重启实测与「A2 路线不可行」的结论（同日第三轮）

### 7.1 实测结果（重启蓝牙 + 抓日志）

重启后（新 pid），模块侧与 native 侧**均确认地址键生效**：

```
lea-whitelist: **:**:**84:85 add=true match=true (+1500ms / +4000ms / +10000ms)
lea-whitelist: **:**:**3C:62 add=true match=true (+1500ms / +4000ms / +10000ms)

D bt_device_interop: [interop_oplus.cc:729] interop_oplus_match_addr:
    OplusLeAudioWhiteList, check xx:xx:xx:xx:84:85 return true
D bt_device_interop: [interop_oplus.cc:729] interop_oplus_match_addr:
    OplusLeAudioWhiteList, check xx:xx:xx:xx:3c:62 return true
```

但 native 的 LEA 判定**仍为 false**：

```
D Bluetooth: [oplus_btm_sec.cc:161] oplus_btm_sec_should_retry_le_encrypt:
    addr=xx:xx:xx:xx:84:85, is_oplus_lea=false,
    vendor_id=0x079a, product_id=0x062014, lea_bit_set=0, ...
```

`vendor_id/product_id` 有值 ⇒ 前两把键被读到；`is_oplus_lea=false` ⇒ 后续判据不满足。

### 7.2 完整判据链（反汇编确认）

`oplus_btif_is_lea_device` @ `0x643180`：

```c
bool oplus_btif_is_lea_device(RawAddress& addr) {
    int vendor, product, v;
    if (!btif_config_get_int(addr, "broadcastVendorId",   &vendor))  return false; // 0x6432cc
    if (!btif_config_get_int(addr, "broadcastProductId",  &product)) return false; // ~0x6433xx
    if (!btif_config_get_int(addr, "customLeaBitWritten", &v))       return false; // 0x6434e0
    if (v != 1)                                                      return false; // 0x6434f0
    return interop_match_vendor_and_3bytes_product_id(                             // 0x643508
               "OplusLeAudioWhiteList", vendor, product);                          // str @ 0x6d7b3
}
```

`interop_match_vendor_and_3bytes_product_id` @ `0x11128b0`：

```c
property_get("persist.sys.oplus.bt.le_audio", buf, "false");   // 0x1269e3 / 默认 0xa31d
if (strcmp(buf, "true") != 0) return false;                    // 0x1112910..0x1112928
return real_match(feature, vendor, product);                   // 0x10f5830
```

`real_match` @ `0x10f5830` → `0x1100f80`（按 feature 取条目）→ `0x1101c70`（逐条比
vendor/product，真正比较在 `0x111ef80`）。

**因此 `is_oplus_lea == true` 需要同时满足 4 个条件：**

| # | 条件 | 当前状态 |
| --- | --- | --- |
| 1 | `bt_config.conf` 有 `broadcastVendorId` | ✅ `0x079A`（手工写入） |
| 2 | `bt_config.conf` 有 `broadcastProductId` | ✅ `0x062014`（手工写入） |
| 3 | `bt_config.conf` 有 `customLeaBitWritten = 1` | ❌ **缺失** |
| 4 | `(vendor, product)` 在硬编码白名单里 | ❌ **不在** |

另有属性总开关 `persist.sys.oplus.bt.le_audio` = `true` ✅（还有
`persist.sys.oplus.bt.le_audio_white_list_enable` = `true`）。

### 7.3 硬编码白名单（决定性）

`.rodata` @ `0x1cd674` 起，**每条 0x44 字节**：`name[0x3b] + vendor(u32 LE) + product(u32 LE)`。
`name` 全为 `OplusLeAudioWhiteList`。有效记录 **7 条**：

| # | vendor | product (hex) | product (dec) |
| --- | --- | --- | --- |
| 0 | `0x079A` | `0x063014` | 405524 |
| 1 | `0x079A` | `0x064014` | 409620 |
| 2 | `0x079A` | `0x066010` | 417808 |
| 3 | `0x079A` | `0x067410` | 423952 |
| 4 | `0x079A` | `0x06E010` | 450576 |
| 5 | `0x079A` | `0x06EC10` | 453648 |
| 6 | `0x079A` | `0x067814` | 424980 |

**AZ100 的 `0x062014` (401428) 不在表中。** 表位于 `.rodata`，是编译期常量。

### 7.4 为什么改 conf 无法绕过

* 白名单是 `.rodata` 常量，运行时无法通过任何配置文件注入；
* `libbluetooth_jni.so` 只导出这些 interop JNI：`interopDatabaseAddByUplayerNative`、
  `interopDatabaseRemoveByUplayerNative`、`interopDatabaseMatchNative`、
  `interopDatabaseMatchByTypeNative`、`interopDatabaseAddRemoveAddrNative`、
  `interopDatabaseAddRemoveNameNative` —— **没有 vendor/product 的 add 接口**；
* 我们的模块走 `interopDatabaseAdd`，落到 `interop_oplus_match_addr`（**按地址**匹配），
  与 `interop_match_vendor_and_3bytes_product_id`（**按 vendor/product** 匹配）是
  **两套互不相干的判据**。地址键为 true 并不能让 vendor/product 判据通过。
* 旁证：`a_bt_remote_dev_info.conf` 里 AZ100 的 `Manufacturer = 148`（`0x0094`），
  与白名单的 `0x079A` 并非同一命名空间。

**⇒ 方案 A2（手工改 `bt_config.conf`）从原理上走不通，可以放弃。**

### 7.5 `oplus_handle_filter_uuid` 过滤的 4 个 UUID（从活进程内存读出）

`.bss` 常量在 `.rodata` 中无对应模板，改为**直接读蓝牙进程内存**（base + 偏移）：

| `.bss` 偏移 | 值 | 名称 |
| --- | --- | --- |
| `0x11bae30` | `00001844-0000-1000-8000-00805F9B34FB` | Volume Offset Control Service |
| `0x11bae40` | `00001846-0000-1000-8000-00805F9B34FB` | Media Control Service |
| `0x11bae50` | `0000184E-...` | **PACS（LE Audio 核心）** |
| `0x11bae80` | `0000184F-...` | Basic Audio Announcement |

（`0x11bae60` 是相邻的 128-bit UUID 变量，不参与过滤。）

`oplus_handle_filter_uuid` @ `0x65c250`：命中任一 UUID → 调
`oplus_btif_is_lea_device`（`0x643180`）；返回 false 则 `return false`（丢弃该 UUID）。
**它用的是同一个闸门**，所以闸门一旦为 true，这里自然放行，不需要单独处理。

### 7.6 结论与可选方向

**A2 不可行。** 让 native 认账只剩三条路：

* **B（推荐）**：native inline hook，把 `oplus_btif_is_lea_device`（`base + 0x643180`）
  直接改成 `return true`。需要模块内置 native 库（ShadowHook / Dobby）。优点：不依赖任何
  config 键，三键与白名单全部绕过，8 个调用点 + UUID 过滤器一起放行。
* **C**：Magisk 模块替换 `/system/lib64/libbluetooth_jni.so`，在白名单表补一条
  `(0x079A, 0x062014)`。改动大、系统升级即失效、校验风险高。
* **D**：恢复 `SPLICE_LE_AUDIO_UUIDS`（已撤），继续与 Java 层过滤器对抗（历史上是崩溃源）。

设备当前状态：AZ100 已配对**未连接**（`[ACL BR/EDR:N LE:N]`，`mUuidsLe = null`）；
`bt_config.conf` 中两把键仍在（对结论无效但无害）。

---

## 8. 方案落地：native inline patch（同日第四轮）

用户决策：走 **native inline hook**，并把不再需要的东西**全部撤掉**。

### 8.1 目标与手法

不 hook、不 trampoline，直接改写 `oplus_btif_is_lea_device`（`0x643180`）的序言：

```
原始：                          改写后：
+0x00  d503245f  bti c          +0x00  d503245f  bti c          （保留）
+0x04  f800865e  str x30,[x18]  +0x04  52800020  mov w0, #1
+0x08  d10483ff  sub sp,sp,#0x120  +0x08  d65f03c0  ret
```

* 保留 `bti c`：该页是 BTI-guarded，函数指针式调用需要合法着陆点。
* 两条 store 顺序固定：**先写返回值、后写 `ret`**。中间态是
  `bti c; mov w0,#1; sub sp,sp,#0x120 …`，即"原函数体照常执行"，
  x18（Android shadow call stack）记账完整；反过来先写 `ret` 会少一次
  shadow-stack push，直接把栈弄坏。
* 改动只发生在进程私有页上（COW），**磁盘上的 `.so` 一个字节都没动**，
  重启即失效，不涉及系统完整性。

### 8.2 交付物

| 文件 | 作用 |
| --- | --- |
| `app/src/main/cpp/az100_patch.c` | native 补丁源码（约 200 行，含完整推导注释） |
| `app/src/main/jniLibs/arm64-v8a/libaz100patch.so` | 编译产物，6480 字节，aarch64 |
| `app/src/main/java/com/tosasitill/az100/NativeLeaDeviceHook.java` | 加载 + 触发（三级加载回退） |

编译（本机 NDK r27d，`27.3.13750724`，clang 18）：

```bash
NDKBIN=/Applications/AndroidNDK13750724.app/Contents/NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin
"$NDKBIN/aarch64-linux-android29-clang" -shared -fPIC -O2 -Wall -Wextra -Wl,-s \
    -o app/src/main/jniLibs/arm64-v8a/libaz100patch.so \
    app/src/main/cpp/az100_patch.c -llog -ldl
```

触发时机：hook `AdapterService#onCreate`，之后按
`{800, 2000, 5000, 12000} ms` 重试（幂等，`rc==0||1` 即停）。
`.so` 三级加载：`System.loadLibrary` → `System.load(apk!/lib/...)` →
从 apk 解压到 `codeCacheDir` 再 `load`。实测第一级即成功。

### 8.3 踩到的三个坑

1. **`bti c` 的字节序写错**。`d503245f` 的小端字节是 `5f 24 03 d5`，
   最初写成了 `1f 24 03 d5`。库照样加载、照样调用，但签名校验拒绝改写并
   打印实际字节 —— 保护逻辑按设计工作，日志一次就定位了。
2. **`/proc/self/mem` 被 SELinux 拒绝**。`bluetooth` 域打不开自己的
   `self:mem`（`Permission denied`），所以 FOLL_FORCE 那条路在这台机器上
   根本走不通。改为 **`mprotect` 回退**：先试 `RWX` 一步到位，失败再走
   `RW → 写 → RX`。实测 `RWX` 成功，`poke via mprotect(RWX)`。
3. **shell 算术截断**（沿用上一轮）：Android mksh 的 `$((0x…))` 是 32 位，
   大地址必须在宿主侧算好十进制再传。

### 8.4 验证

`rc = 0`，随后从活进程内存读回目标地址（`base = 0x72e76d9000`）：

```
5f 24 03 d5   20 00 80 52   c0 03 5f d6   fd 7b 0d a9
bti c         mov w0,#1     ret           （原第 4 条指令）
```

前 12 字节与设计完全一致。**闸门已开。**

### 8.5 全撤（用户要求）

`NATIVE_LEA_WHITELIST` 置 `false`，`NativeLeaWhitelistHook` 不再安装
（代码保留、Javadoc 标注 retired）。设备侧由
`revert-lea-config.device.sh` 完成（停适配器 → 杀进程 → 改文件 → 启适配器）：

* `bt_config.conf` 中 `broadcastVendorId` / `broadcastProductId` 已删除
  （`grep broadcast` 返回空）；
* `bt_iot_update_list.conf` 中 `[OplusLeAudioWhiteList]` 段已删除；
* `[OplusDelayConnectProfile1S]` 的 AZ100 条目是 stock 代码写的，保留。

### 8.6 剩余事项

* 端到端待验证：AZ100 连接后应看到 `is_oplus_lea=true`，且
  `oplus_handle_filter_uuid` 不再丢弃 `184E/184F/1846/1844`。
* 该 patch 是**进程级**的，蓝牙进程每次重启由模块自动重打（已验证）；
  模块被禁用或 LSPosed 作用域未包含 `com.android.bluetooth` 时不会生效。
* 固件升级后 `0x643180` 的序言若变化，签名校验会拒绝改写并打印实际字节，
  需要重新定位（该函数是 `libbluetooth_jni.so` 里的内部符号，无导出名可查）。

---

## 9. 用户实测反馈「一打开 lea 蓝牙就崩溃重启、永远无法连上」——根因定位（15:2x）

### 9.1 一句话结论

**§8 的 native patch 是生效的**（`184E/184F` 确实不再被丢弃，见 9.2），
但连接流程被 **OPPO 自己的一个死字段 bug** 挡住：

> `com.android.bluetooth.bass_client.BassClientService.mOplusBassClientService`
> 在这个 ROM 里**从未被赋值**，而 `BassClientService.connect()` 第 3545 行
> **无判空**地解引用它 → NPE → 从 `AdapterService.connectAllEnabledProfiles()`
> 抛出 → 调用方 App 崩溃，且**所有 profile 的连接全部中止**
> （所以 `[ACL BR/EDR:N LE:N]`，永远连不上）。

即：patch 没写错，它只是把 OPPO 的隐藏 bug 从「不可达」变成了「可达」。

### 9.2 patch 生效的直接证据

```
$ dumpsys bluetooth_manager | grep -A2 Bonded
XX:XX:XX:XX:84:85(Public ) ... [ DUAL ] [ACL BR/EDR:N LE:N] Technics EAH-AZ100
    [LE UUIDs]: 0000180f ... 00001844 ... 00001846 ... 0000184e ... 0000184f
XX:XX:XX:XX:3C:62(Public ) ... [  LE  ] ...
    [LE UUIDs]: 0000180f ... 00001844 ... 00001846 ... 0000184e ... 0000184f
```

`184E`(PACS) 与 `184F`(BASS) 都在。回读进程内存也仍是
`5f 24 03 d5 20 00 80 52 c0 03 5f d6`。

### 9.3 崩溃现场（logcat，15 次相同 NPE，跨 6 个进程）

进程：`2847 com.oplus.wirelesssettings`、`3260`、`32113`、`32252`、`32395`、`11680`。

```
E AndroidRuntime: FATAL EXCEPTION: main
E AndroidRuntime: java.lang.RuntimeException: Error receiving broadcast
    Intent { act=android.bluetooth.device.action.UUID ... }
    in com.android.settingslib.bluetooth.BluetoothEventManager$BluetoothBroadcastReceiver
E AndroidRuntime: Caused by: java.lang.NullPointerException: Attempt to invoke virtual method
    'void com.android.bluetooth.bass_client.OplusBassClientService.checkDeviceVcsReconnect(
        android.bluetooth.BluetoothDevice)' on a null object reference
E AndroidRuntime: 	at android.os.Parcel.createExceptionOrNull(Parcel.java:3428)
E AndroidRuntime: 	at android.bluetooth.IBluetooth$Stub$Proxy.connectAllEnabledProfiles(IBluetooth.java:3744)
E AndroidRuntime: 	at android.bluetooth.BluetoothDevice.connect(BluetoothDevice.java:2347)
E AndroidRuntime: 	at com.android.settingslib.bluetooth.CachedBluetoothDevice.connectDevice
E AndroidRuntime: 	at com.android.settingslib.bluetooth.CachedBluetoothDevice.onUuidChanged
E AndroidRuntime: 	at BluetoothEventManager$UuidChangedHandler.onReceive
```

注意 `Parcel.createExceptionOrNull` —— 说明 NPE 是**在 `com.android.bluetooth`
进程内抛出**，再经 binder 回传给调用方。因此 `connectAllEnabledProfiles`
整体失败。

### 9.4 死字段的 smali 铁证

```
$ apktool d -r -f -o /tmp/btapk /tmp/Bluetooth.apk
$ grep -n "mOplusBassClientService" /tmp/btapk/smali/com/android/bluetooth/bass_client/BassClientService.smali
214:   .field private mOplusBassClientService:...OplusBassClientService;
2529:  iget-object v0, p0, ...->mOplusBassClientService:...   # 读
8165:  iget-object p0, p0, ...->mOplusBassClientService:...   # 读
15101: iget-object p0, p0, ...->mOplusBassClientService:...   # 读
15705: iget-object p0, p0, ...->mOplusBassClientService:...   # 读
17854: iget-object p0, p0, ...->mOplusBassClientService:...   # 读
17873: iget-object p0, p0, ...->mOplusBassClientService:...   # 读
18905: iget-object p0, p0, ...->mOplusBassClientService:...   # 读
19367: iget-object p0, p0, ...->mOplusBassClientService:...   # 读
```

1 处字段声明 + **8 处 `iget-object`（读）**，**0 处 `iput-object`（写）**。
跨整个 APK 搜 `mOplusBassClientService` 也只命中这一个文件。
→ 该字段恒为 `null`。`OplusBassClientService.getInstance(...)` 在整个
反编译结果里**没有任何调用者**。

3 个未判空的解引用点：

| 行号 | 方法 | 说明 |
| --- | --- | --- |
| `935` | `checkDuplicateSourceAdditionAndGetSourceId` | 无判空 |
| `2036` | （`mLocalBroadcastMetadata != null` 分支内）| 无判空 |
| `3545` | `connect(BluetoothDevice)` | **本次崩溃点** |

另 5 处（`3400` / `4009` / `4017` / `4210` / `4297`）作者都写了
`if (oplusBassClientService != null)` —— 说明作者知道它会为 null，只是漏了 3 处。

### 9.5 为什么以前不崩、现在才崩

`BassClientService.connect()` @ 3529 开头：

```java
if (!Util.arrayContains(getAdapterService().getRemoteUuids(device), BluetoothUuid.BASS)) {
    Log.w(TAG, "connect: Cannot connect to ... : Remote does not have BASS UUID");
    return false;                       // <- 以前都从这里提前返回
}
...
this.mOplusBassClientService.checkDeviceVcsReconnect(device);   // <- 3545，现在才走到
```

`BluetoothUuid.BASS` = `0000184F`。以前 `184F` 被 `oplus_handle_filter_uuid`
丢掉 → 提前 return → 永不 NPE。§8 的 patch 让 `184F` 保留 → 走到 3545 → NPE。

### 9.6 Java 侧 LE Audio 总闸门（反编译 Bluetooth.apk 确认）

`PhonePolicy.processInitProfilePriorities(device, uuids)` @ `PhonePolicy.java:389`：

```java
boolean z  = SystemProperties.getBoolean("persist.bluetooth.leaudio.bypass_allow_list", false);
boolean zIsLeAudioOnlyDevice = isLeAudioOnlyDevice(device, uuids);
boolean zShouldEnableHapByDefault = shouldEnableHapByDefault(device, uuids);
boolean z2 = leAudioService.isPresent()
    && Util.arrayContains(uuids, BluetoothUuid.LE_AUDIO)               // (2) 需 184E
    && leAudioService.get().getConnectionPolicy(device) != 0           // (3)
    && (mLeAudioEnabledByDefault || Utils.isDualModeAudioEnabled())    // (4)
    && (z || zShouldEnableHapByDefault
        || mAdapterService.isLeAudioAllowed(device)                    // (5)
        || zIsLeAudioOnlyDevice);
// z2 就是 isLeAudioProfileAllowed，false 则 LE Audio 永远不连
```

各条件实测：

| # | 条件 | 来源 | 实测 |
| --- | --- | --- | --- |
| 2 | `184E` 在 device UUID 里 | GATT 发现 + `oplus_handle_filter_uuid` | ✅ 已放行 |
| 3 | LeAudio 连接策略 ≠ 0 | `LeAudioService` | 待查 |
| 4 | `mLeAudioEnabledByDefault \|\| isDualModeAudioEnabled()` | `ro.bluetooth.leaudio.le_audio_connection_by_default`（默认 false，**未设置**）\| `persist.bluetooth.enable_dual_mode_audio`（**未设置**） | dumpsys 打印 `isDualModeAudioEnabled: true` → 视为 ✅（与属性状态矛盾，未解） |
| 5a | `z` = bypass 属性 | `persist.bluetooth.leaudio.bypass_allow_list` | ❌ 未设置 |
| 5b | HAP by default | `bluetooth.profile.hap.enabled_by_default`（默认 true）+ 需 `BluetoothUuid.HAS` | ❌ 无 HAS |
| 5c | `isLeAudioAllowed()` | `AdapterService.java:3362` = `mLeAudioAllowDevices.contains(DeviceProperties.getModelName())` | ❌ 列表为空 |
| 5d | `isLeAudioOnlyDevice()` | 需 `getRemoteType()==2`（LE-only）| ❌ AZ100 是 DUAL |

`isLeAudioAllowed` 的白名单来自 `cacheLeAudioAllowlistDevicesFromProp()`
@ `AdapterService.java:1864`：

```java
List list = BluetoothProperties.le_audio_allow_list();   // DeviceConfig bluetooth/le_audio_allow_list
String str = SystemProperties.get(LE_AUDIO_ALLOW_LIST_EXTEND, "");   // <- 关键属性
```

`LE_AUDIO_ALLOW_LIST_EXTEND = "persist.bluetooth.leaudio.allow_list_extend"`
（`AdapterService.java:202`，`static final`）。实测该 DeviceConfig 为 `null`、
该属性为空 → 白名单为空 → 条件 5c 恒 false。

**「开不了 lea」的直接原因 = 条件 (5) 恒 false。**
原生、干净的解法（AOSP/OPPO 本来就支持的属性，不需要任何 patch）：

```bash
setprop persist.bluetooth.leaudio.bypass_allow_list true
# 或按机型名精确加入（isLeAudioAllowed 比的是 DeviceProperties.getModelName()）
setprop persist.bluetooth.leaudio.allow_list_extend "Technics EAH-AZ100"
```

### 9.7 已排查掉的可能

* **SIGKILL 不是主因**：死亡日志是
  `Zygote: Process N exited due to signal 9 (Killed)`，**无 tombstone、无 Java 栈**；
  `AdapterBinder.killBluetoothProcess()` 会打 `BluetoothAdapterBinder` 标签，
  日志里 0 命中；LMK 明确 `disable killing`；dmesg 无记录。
  更像是连接失败后的连锁重启。
* 模块自身**没有**任何 `killProcess` / `System.exit` / `Runtime.exec`（已 grep）。
* `AdapterService.connectEnabledProfiles` @ 743：`!OplusUtils.useOrigin()` 时
  直接委派给 `mOplusAdapterService.oplusConnectEnabledProfiles(device)`
  （实现在 `oplus-framework.jar`，不在 Bluetooth.apk 里）。
* `1844/1846/184E/184F` 是 AZ100 真实 GATT 服务（VOCS/MCS/PACS/BASS），
  不是 patch 伪造的。

### 9.8 下一步（待用户批准）

1. **修 NPE（必须）** —— hook `BassClientService` 构造函数（after），
   若 `mOplusBassClientService == null`，用
   `OplusBassClientService.getInstance(self, adapterService, mBcsWrapper)`
   造一个真实实例并 `setObjectField` 写回。
   这样 935 / 2036 / 3545 三处都安全，且保留 OPPO 原本想做的 VCS 重连逻辑。
   备选（更保守、改动更小）：hook `BassClientService.connect(BluetoothDevice)`，
   字段为 null 时直接 `param.setResult(false)`。
2. **开闸（必须）** —— `setprop persist.bluetooth.leaudio.bypass_allow_list true`
   然后重启蓝牙。

### 9.9 本轮新增的可复用工具链

| 用途 | 命令 |
| --- | --- |
| 拉取 Bluetooth.apk | `adb pull /system/app/Bluetooth/Bluetooth.apk`（18.8 MB，Android 17 / SDK 37）|
| 单类反编译（快） | `jadx --single-class com.android.bluetooth.btservice.AdapterService -d out Bluetooth.apk` |
| 全量反编译 | `jadx -d /tmp/btfull --no-res /tmp/Bluetooth.apk` |
| **判断死字段**（最快） | `apktool d -r -f -o /tmp/btapk Bluetooth.apk`，再 grep smali 里的 `iget-object` / `iput-object` |
| 按 uid 抓蓝牙日志 | `adb logcat -d --uid=1002`（bluetooth = uid 1002）|

（`jadx`、`apktool` 都在 `/opt/homebrew/bin`。）

---

## 10. 修复落地 + 对 §9.6 的更正

### 10.1 ⚠️ 更正：shell 域 `getprop` 不可信，§9.6 结论有误

`adb shell getprop <name>` 跑在 **shell 域**。部分属性在 `property_contexts`
里有读限制，**即使已设置也返回空串**。必须用 `su -c 'getprop ...'`（root 域）读。

root 重读的真实值：

| 属性 | shell 读 | **root 读（真实）** |
| --- | --- | --- |
| `persist.bluetooth.leaudio.bypass_allow_list` | （空）| **`true`** |
| `persist.bluetooth.enable_dual_mode_audio` | （空）| **`true`** |
| `persist.bluetooth.leaudio.allow_list_extend` | （空）| （空）|
| `ro.bluetooth.leaudio.le_audio_connection_by_default` | （空）| （空）|
| `persist.sys.bt.leacoexist` | — | `true` |

所以 §9.6 里「条件 (5) 恒 false、需要 `setprop` 开闸」**是错的**：
`bypass_allow_list` 本来就是 `true`，`PhonePolicy.java:389` 的第 5 个条件
**一直满足**。`persist.bluetooth.enable_dual_mode_audio=true` 也顺带解释了
§9.6 里那个「属性未设置却报 `isDualModeAudioEnabled: true`」的矛盾。

**修正后的完整因果：**

| 阶段 | 184E | 184F | 结果 |
| --- | --- | --- | --- |
| native patch 之前 | 被 `oplus_handle_filter_uuid` 丢弃 | 丢弃 | `PhonePolicy` 条件 (2) false → LE Audio 永不连接 = **「开不了 lea」** |
| native patch 之后 | 放行 | 放行 | 条件 (2) 满足 → 开始连 LE Audio；但 BASS 走到 3545 → NPE → `connectAllEnabledProfiles` 整体失败 = **「崩溃 + 永远连不上」** |
| NPE 修复之后 | 放行 | 放行 | 两条路都通 |

`PhonePolicy.java:389` 的五个条件里，(2)(3)(4)(5) 在本机**全部为真**
（(3) 由 dumpsys 的 `le_audio: ALLOWED` 确认），唯一的拦路虎是那个 BASS NPE。

### 10.2 修复 1 —— OPPO 死字段 NPE

新增 `app/src/main/java/com/tosasitill/az100/BassClientFixHook.java`：

1. hook `BassClientService` 两个构造函数的 after
   （`(AdapterService, ScanController)` 与 `(AdapterService, ScanController, Looper)`）；
   若 `mOplusBassClientService == null`，用
   `OplusBassClientService.getInstance(self, getAdapterService(), mBcsWrapper)`
   造出真实实例并 `setObjectField` 写回。
   → `935` / `2036` / `3545` 三个未判空点全部安全，**且** OPPO 原本想做的
   VCS 重连逻辑真正开始工作（而不是被跳过）。
2. 第二道防线：hook `connect(BluetoothDevice)`，before 时若字段仍为 null
   则 `param.setResult(false)`，绝不让它抛。
3. 由 `LeAudioTransportHook.BASS_NPE_FIX = true` 控制开关。

### 10.3 修复 2 —— 属性

`persist.bluetooth.leaudio.bypass_allow_list` 实测**本来就是 `true`**，
脚本 `open-lea-gate.device.sh` 只是幂等地重写了一遍（无害）。

### 10.4 实测验证

装包 + 重启蓝牙后，模块日志：

```
1791790827691 native: AdapterService#onCreate hooked
1791790828892 native: loaded via loadLibrary
1791790828892 native: patch rc=0 (+800ms)
1791790828974 bass-npe: injected OplusBassClientService into BassClientService
```

native patch 重打成功 + NPE 修复生效。

### 10.5 ⚠️ 遗留风险：A2DP / HFP 被禁用

`dumpsys bluetooth_manager` 里 AZ100 的策略：

```
a2dp: FORBIDDEN      hfp: FORBIDDEN
bass_client: ALLOWED  le_audio: ALLOWED  vcp: ALLOWED  csip: ALLOWED  battery: ALLOWED
```

`a2dp` / `hfp` 的 **FORBIDDEN** 是 `PhonePolicy` 主动设的
（`PhonePolicy.java:418-420` 与 `431-433`，日志文案
`"Dual mode device detected: clear hfp/a2dp profile priority"`）。
含义：**只要 LE Audio 没真正连上，经典 A2DP/HFP 也是禁用的 → 完全没声音。**
修好 NPE 后 LE Audio 应能接管；若仍不行，需要单独把 a2dp/hfp 的策略改回 ALLOWED。

### 10.6 流程约定

用户批准「改代码 / 编译 / 安装 APK」**不等于**批准「重启蓝牙」。
`svc bluetooth disable/enable`、`kill -9 $(pidof com.android.bluetooth)`
这类会打断用户当前耳机使用的动作**必须单独确认**。已固化进用户级第一指令。

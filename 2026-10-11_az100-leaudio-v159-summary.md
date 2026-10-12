# AZ100 LE Audio / LC3 修复 — v1.5.9 技术总结

> 这是 v1.5.9 当天的工作记录。当前版本是 1.7.9，运行行为以 `README.md` 为准。

> 设备：OnePlus PLJ110（ColorOS / Android 16，构建 `PLJ110_17.0.0.101(CN01)`）
> 耳机：Technics EAH-AZ100，两个 LE 身份 `B8:20:8E:EE:84:85`（左，DUAL）与 `B8:20:8E:EE:3C:62`（右，LE only），同属 CSIS group 1
> 模块：route B 在 `com.android.bluetooth` 内运行（原 `com.oplus.melody` 规则的唯一例外，已用户授权）
> 日期：2026-10-11

---

## 1. Primary Request and Intent

用户的最终诉求（按时间顺序收敛）：

1. 继续此前会话的 LE Audio / LC3、双耳连接与音量控制排查，保持同等详细程度。
2. 修复连接退化：用户反馈「现在耳机又连不上了」，要求不能只顾音量、必须把双耳连接稳定性也修好。
3. **将最低非零 VCS 音量从厂商的 16 放开到 1**：用户原话「下降到 1 吧」。
4. **检查单耳使用是否有 bug**：只用左耳、只用右耳、双耳↔单耳热切换是否受影响。
5. 最后要求：生成严格按九个章节组织的技术总结，并把改动推送到仓库。

持续有效的约束：

- 不模拟 UI 点击；诊断与驱动只走 `adb`/`su` 与 route B 的进程内 API。
- LSPosed 作用域由用户手动勾选，模块不写 LSPosed 配置数据库。
- 不擅自取消配对、清空蓝牙数据或恢复出厂。本次仅做了一次蓝牙开关重启以加载新模块（会短暂断耳）。
- 中文沟通。

---

## 2. Key Technical Concepts

- **两个耳机身份与 LE Audio 集合**：两个地址都显示 `Technics EAH-AZ100`、CoD `0x240404`；实际连接与音量服务运行在 `com.android.bluetooth`，不在 `com.oplus.melody`。
- **此前已修复的两层厂商限制**（本会话之前）：
  - 厂商剥离 LE Audio UUID → 模块补回 `0x184e ASCS` / `0x1846 CSIS` / `0x1844 VCS`。
  - VCP connection policy 被设为 `FORBIDDEN=0` → `okToConnect` 只接受 `UNKNOWN=-1` 或 `ALLOWED=100`；补 `allowVcp()` 与 `setConnectionPolicy` 守卫。
- **厂商真正生效的音量下限**：`VolumeControlNativeInterface.smali` 中 `setGroupVolume(II)V` 与 `setVolume(Device;I)V` 都执行 `if (volume != 0 && volume < 16) volume = 16;`，常量 `LEAUDIO_VOLUME_MIN = 0x10`。0 保持静音，1..15 被抬到 16。
- **回报方向的隐藏下限**（本次新发现）：`VolumeControlService.handleVolumeControlChanged` 的 autonomous 组级分支（`p6=true` 且 `device=null`）也会把 `volume <= 15` 归 0（`if (volume >= 240) volume = 255; if (volume <= 15) volume = 0;`）。仅绕过下发 clamp 不够，单耳低音量重连仍可能变静音。
- **callback 签名**：`handleVolumeControlChanged(BluetoothDevice;IIIZZ)` = `(device, groupId, volume, flags, mute, autonomous)`。`device=null` 是正常组级事件，不代表缺设备；`p6` 是 `autonomous`，连接初始音量上报也走此分支。
- **防重队列**：同值 `SetAbsoluteVolume` 不产生 Volume State 通知 → 原生 VCP 操作队列等待完成而卡死（证据：ATT 写入返回 `status=0x0`，但无对应 state 更新，`OperationMonitorTimeoutFired` 持续）。守卫放在最后一道 Java→native 边界。
- **单耳成员隔离**：VCS 去重与缓存必须只针对「当前 VCP 已连接」的 AZ100 成员；一只耳断连不应清掉另一只耳的状态。组级回调不能当作全耳确认。
- **双耳补连**：原栈只拨号一个成员，另一只耳需模块补 `connect`；需避免对正在 Connecting / 显式 Disconnecting 的成员重复请求，并限频。

---

## 3. Files and Code Sections

### 修改的文件

- `app/src/main/java/com/tosasitill/az100/LeAudioTransportHook.java`（route B 主文件）
  - 常量：
    ```java
    private static final boolean LOWER_VOLUME_FLOOR = true;   // 放开 1..15
    private static final boolean TWIN_AUTO_CONNECT = true;    // 双耳补连
    private static final int LEAUDIO_VOLUME_MIN = 0x10;
    ```
  - 音量缓存改为按成员隔离（替换旧的全局 `LAST_PUSHED`/`LAST_REPORTED` 全量比较）：
    ```java
    // 只对当前 VCP 已连接的 AZ100 成员求共识；新 push 覆盖旧 report；
    // autonomous report 覆盖旧 push；A->B->A 不再被旧 A 误拦。
    private static java.util.List<BluetoothDevice> volumeMembers(String key) { ... }
    private static void rememberPushed(List<BluetoothDevice> members, int volume) { ... }
    private static void forgetVolume(BluetoothDevice device) { // 仅清该耳 }
    private static void captureVolumeReport(Object target, int groupId, int volume, boolean autonomous) { ... }
    ```
  - 下发 bypass（直接调私有 native，跳过厂商 16 抬高）：
    ```java
    int volume = (LOWER_VOLUME_FLOOR && wanted > 0 && wanted < LEAUDIO_VOLUME_MIN) ? wanted : stock;
    if (volume != stock && writeNative(param.thisObject, args[0], volume)) {
        rememberPushed(members, volume);
        param.setResult(null);   // 仅 bypass 成功才吞原方法
        return;
    }
    // 失败则走原 wrapper（会发 stock=16），并在 after-hook 记录实际值
    ```
    `writeNative` 调 `setGroupVolumeNative(II)` 或 `setVolumeNative([B I)`。
  - 低值 autonomous 回报保护（避免厂商 <=15 归 0）：
    ```java
    private static boolean preserveLowAutonomousReport(Object vcs, Object[] args) {
        if (volume < 1 || volume >= LEAUDIO_VOLUME_MIN) return false;
        if (!az100Group(groupId)) return false;
        // 仅当 stream=STREAM_MUSIC、组已 active、单耳路径要求 flags&1 且恰好 1 成员
        // 用原 private updater(updateCacheByAutonomousChange) + notifyDevicesVolumeChanged 以原始低值更新
        return true;
    }
    ```
  - 双耳补连加保护（`connectTwin`）：
    ```java
    int state = getConnectionState(twin);
    if (state != 0 || TWIN_BLOCKED.contains(twinAddress)) return;  // 跳过 Connected/Connecting/Disconnecting
    if (twin.getBondState() != BOND_BONDED) return;
    int policy = getConnectionPolicy(twin);
    if (policy != ALLOWED && policy != UNKNOWN) return;
    // 60s 限频，单次 best-effort，绝不重试循环
    if (now - last < TWIN_COOLDOWN_MS) return;
    ```
    新增 `disconnect` before-hook：显式断开的成员加入 `TWIN_BLOCKED`，避免模块立刻补连；`connect` after-hook 在原 connect 成功后才清 `TWIN_BLOCKED`。
  - 控制台新增 `vcs-low <mac> <0..15>`：经 `setGroupVolume` 提交低值（仅提交证据，非 ACK），便于无 UI 点击验证。
  - `keyOf` 仅对 AZ100 成员返回键，其他蓝牙设备完全不走 floor / 防重逻辑。
- `app/build.gradle`：`versionCode 17 → 18`，`versionName '1.5.8' → '1.5.9'`。
- `README.md`、`Az100Hook.java`、`Logs.java`、`MelodyProviderHook.java`：此前会话改动，本会话一并纳入提交。

### 反编译参考（只读，未改）

- `/tmp/melody/smali_full2/smali/com/android/bluetooth/vc/VolumeControlNativeInterface.smali`：line 17 `LEAUDIO_VOLUME_MIN=0x10`，409/426 下发 clamp，121/124 私有 native setter。
- `.../vc/VolumeControlService.smali`：5443 callback 签名；2795 `connectionStateChanged(Device;II)`（from→to）；2120 `updateCacheByAutonomousChange`；6082–6093 autonomous 归零分支；6295–6375 组/设备通知。
- `.../le_audio/LeAudioService.smali`：`connect`/`disconnect`/`getConnectionState`/`getGroupDevices`/`getGroupId` 公开签名。

### 产物

- `app/build/outputs/apk/debug/app-debug.apk`（v1.5.9 / versionCode 18），已 `adb install -r` 到手机，LSPosed 需用户勾选 `com.android.bluetooth`（route B 例外）。

---

## 4. Errors and fixes

- **过低音量只绕过下发不够**：复查发现 autonomous 回报方向也有 `<=15 → 0`。已加 `preserveLowAutonomousReport`，用原 private cache/audio updater 以原始低值更新，不再被归零。
- **单耳断连清掉双耳状态**：原 `forget` 清全部 `LAST_PUSHED`/`LAST_REPORTED`。改为 `forgetVolume(device)` 仅清该耳；`connectionStateChanged` 的 `toState != 2` 触发。
- **防重误拦 A→B→A**：原「reported 或 pushed 任一相等即拦」在快速往复时错误抑制。改为仅对「当前已连接成员共识」判断，且新 push 覆盖旧 report、autonomous 覆盖旧 push。
- **双耳补连对 Connecting / Disconnecting 重复请求**：加 `state != 0` 与 `TWIN_BLOCKED` 检查、bond/policy 前置、60s 限频。
- **USB 在蓝牙重启后掉线**：本会话末尾 `adb devices` 为空，实机验证中断。非代码问题，待重连后续测。
- 历史已修正的过强结论：日志计数（136 SetVolume 等是匹配行数非严格 API 次数）、`Operation 2` 是队列 id 非 opcode、卡死定位为 VCP 操作队列等待完成而非「ATT 无响应」。

---

## 5. Problem Solving

**已完成并有实测证据：**
- v1.5.9 构建成功、`adb install -r` 成功、蓝牙重启后模块钩子全部 armed（日志：`bounded twin auto-connect armed`、`volume-report capture and member lifecycle armed`、`redundant-write guard armed`）。
- 两个 AZ100 地址的 LE UUID 在重启后再次被 splice（84:85 与 3C:62 都补回 `0x184e/0x1846/0x1844`）。
- 此前 v1.5.8 已在重复索引测试中验证防重守卫有效（14 条 control point、0 个 timeout）。

**代码层修复（静态审查完成，待实机验证）：**
- 最低非零 VCS 放开到 1 的 bypass 路径（直接 native setter）。
- 低值 autonomous 回报不再归零。
- 音量缓存按当前连接成员隔离，单耳不影响另一只耳。
- 双耳补连的状态/限频/显式断开保护。

**尚未实机验证（USB 掉线前未跑）：**
- 实际下发 VCS 1..15 是否抵达耳机（预期：绕过 clamp 后原生日志应出现 `vcs: N written straight to native`）。
- 手机最低可见媒体档是否映射成 VCS 1（此前 media index=1 曾产生 wanted=2，需实测确认）。
- 左右单耳分别入盒/取出、双耳↔单耳热切换的音量跳变、缓存失真、队列超时。

---

## 6. All user messages

1. 续前摘要，保持详细程度与帮助方式。
2. `继续`
3. `而且现在又耳机也连不上了`（连接退化）
4. `下降到 1 吧，然后看看我要是单耳使用有没有什么 bug`
5. `继续`
6. 要求生成严格按九个章节的技术总结。
7. `继续`（多次）
8. `总结一下然后推到仓库吧`

---

## 7. Pending Tasks

- 重连 USB 后实机验证：
  - 低音量：用 `vcs-low` 控制台或媒体滑块到最低，确认原生日志出现 1..15 直写、回报不被归零。
  - 单耳：左耳单独（右耳入盒）、右耳单独（左耳入盒）、双耳↔单耳热切换，检查连接/声道/音量/无重复重连/无队列超时。
- 若实测发现单耳异常，回到 `connectTwin` / `captureVolumeReport` / `preserveLowAutonomousReport` 修边界。
- 更新 README 的 v1.5.9 行为说明（当前 README 可能还停留在旧版本）。

---

## 8. Current Work

本次会话完成 v1.5.9 源码修改并安装：
- 放开最低非零 VCS 到 1（bypass 厂商 16 clamp + 保护低值 autonomous 回报不被归零）。
- 音量缓存按当前 VCP 已连接 AZ100 成员隔离，单耳断连只清该耳。
- 双耳补连加 Connected/Connecting/Disconnecting 跳过、bond/policy 前置、60s 限频。
- `git` 远端 `git@github.com:tosasitill/Melody.Az100_Impl.git`，待提交推送。
- USB 在蓝牙重启后掉线，实机低音量/单耳验证未完成；不冒充实测结论。

---

## 9. Optional Next Step

重连 USB 后，按顺序实测（不模拟点击）：
1. `adb shell cmd media_session volume --stream 3 --set 1` 或 `vcs-low B8:20:8E:EE:84:85 1`，查 `/data/misc/bluetooth/az100.log` 是否出现 `vcs: 1 written straight to native (clamp bypassed)` 与回报不再归零。
2. 右耳入盒，确认左耳单独连接、声道、音量正常，无重复补连；再左耳入盒验证右耳。
3. 双耳↔单耳热切换，检查 `OperationMonitorTimeoutFired` 计数与音量跳变。
每一步都先查两耳 profile 状态、真实 VCS 回报与队列超时，拿到证据再判定「单耳无 bug」。

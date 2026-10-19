# LE Audio 流建立失败与约 500 ms 重试循环

日期：2026-10-19。设备与 ROM：OnePlus PLJ110 / ColorOS 17（Android 17）。
本文是静态分析记录；设备端日志由用户复现时抓取，未在本次分析中连接手机。

## 现象

播放媒体时连接状态反复抖动：耳机与手机看起来不断在切换/重连，但 LE ACL
始终没有真正断开。此前这一现象被归因于蓝牙进程崩溃或 ACL 断开，实际都不是。

## 日志证据

用户复现时抓取的详细日志中，同一个 cycle 反复出现：

```text
ASCS_CP_CMD  QOS_CONFIG: ASE 1,ASE 3,   snk,...;;src,...
ASCS_CP_CMD  ENABLE: ASE_ID 1,ASE_ID 3, meta: 03020400020501;;meta: 03020100;;
HCI_EVENT    SET_ISO_DATA_PATH: direction 0x00 STATUS=0000
HCI_EVENT    SET_ISO_DATA_PATH: direction 0x01 STATUS=0x0c
SM           TARGET STATE CHANGED: STREAMING -> IDLE
ASCS_CP_CMD  RELEASE: ASE_ID 1,ASE_ID 3,
AF -->       REQUEST_CANCELED: LocalSource
AF -->       RESUME_REQ: LocalSource
```

要点：

- 媒体流配置了两个 ASE：sink ASE 1（MEDIA，metadata `03020400020501`）和
  source ASE 3（UNSPECIFIED，metadata `03020100`），即双向 CIS。
- 控制器接受 IN 方向的数据通路（`0x00`），拒绝 OUT 方向（`0x01`，
  `HCI_ERR_COMMAND_DISALLOWED = 0x0c`）。
- 失败后 ASE 被 RELEASE，CIS 以 `0x16` 断开，CIG_REMOVE；音频框架马上重新
  `RESUME_REQ`，整个 cycle 约 500 ms 重复一次。
- 同期的 LE ACL 与 BR/EDR ACL 都没有断开，因此“反复连接”是流层面的
  create/release 循环，不是连接层面的掉线。

## AOSP 语义

静态核对 AOSP（`btif_le_audio.cc`、`bta/le_audio/client.cc`、
`bta/le_audio/state_machine.cc`、`device_groups.cc`、`LeAudioService.java`）：

- `ProcessHciNotifSetupIsoDataPath` 把任何数据通路失败视为致命错误，调用
  `StopStream(group)`，释放 ASE、CIS 和 CIG；没有“只保留可用方向”的分支。
- Java 只在 group 真正进入 STREAMING 时收到状态上报，且 native
  `notifyGroupStreamStatus()` 会按状态迁移去重。数据通路失败时永远到不了
  STREAMING，所以 Java 每个会话最多看到一次初始 IDLE，没有逐 cycle 事件。
- AF 的 `RESUME_REQ` 走 native/HAL，不经过 `LeAudioService`，Java hook 无法
  观察，也无法拒绝单次 resume。
- stock AOSP 对 MEDIA / UNSPECIFIED 不要求 source ASE
  （`kLeAudioContextAllRemoteSinkOnly`）；`kLeAudioContextAllBidir` 不含
  MEDIA。为媒体配置 source ASE 是此 ROM/厂商栈的行为。

结论：这是厂商把媒体流配置成双向 CIS 后，控制器拒绝 OUT 数据通路导致的
协议层不兼容；Java 层无法修正控制器方向，只能停止继续请求该路径。

## 为什么不做原生修复

仓库里的 `libaz100_patch.so` 按固定偏移修补 `libbluetooth_jni.so`（LE 白名单
与 SSP 同意）。仓库没有该 ROM 的 `libbluetooth_jni.so`，且设备端禁止运行临时
探针，无法静态确定“忽略数据通路失败并只保留 IN 方向”的补丁位置；强行添加
偏移会带来进程崩溃风险。

## 模块行为（本次修复）

`LeaStreamGuard` 识别“group 是 active 输出、音频正在使用、却持续不 streaming”
的状态，并在确认后把输出交回 classic：

1. `OplusDualModeCoexistHook` 在 LE Audio 激活、播放开始、native 报告流状态
   IDLE 时各安排一次**一次性**探测（无轮询）。激活后 2.5 秒的探测覆盖了
   AF 首次 resume 的时机；播放回调覆盖“group 早已 active、稍后才播放”的情况。
2. 探测要求 active group 中仍有已连接的 AZ100；音频未在使用时视为不可判定
   （正常工作流本身就会在空闲时 IDLE）。
3. 第一次“未 streaming 且音频在用”只记为确认；第二次才触发回退，避免把正常
   启动耗时误判为故障。
4. 触发时先打开 yield window（60 秒，连续触发按 60/120/240/300 秒升级），再
   调用 `LeAudioService.removeActiveDevice(false)`。`setActiveGroupWithDevice`
   的 `handleGroupTransitToInactive()` 会通知 AudioService，声音由仍然连接的
   A2DP 接管；窗口期间模块不自动激活 LE Audio，也不把这次停用误判为交接。
5. 看到 STREAMING（native 上报或 `getGroupStatus()`）或在手机上重新选择
   AZ100 时清零升级计数。

日志关键字：

```text
dual-coexist: LE Audio stream unavailable, group=1 audio=true (leAudioActive)
dual-coexist: leaStreamFallback group=1 hold=60000
dual-coexist: leaYieldWindow opened (leaStreamUnavailable)
```

## 验证边界

- 离线：`LeaStreamGuardTest`（8 个用例）覆盖确认、升级、健康流重置、group
  重建与本地选择；`LeaHandoffStateTest` 新增 3 个用例覆盖加长窗口不会被随后
  的普通交接缩短。2026-10-19 在 JDK 21 下 `:app:testDebugUnitTest` 与
  `:app:assembleDebug` 通过。
- 未验证：本 ROM 上 `removeActiveDevice(false)` 后 AudioService 的实际路由、
  A2DP 是否在所有场景都已连接、播放回调在蓝牙进程内是否收到其他应用的播放
  配置。按 AGENTS.md 的设备验证限制，这些只能由用户在正常使用中确认。
- 已知限制：若 group 长时间 active 后开始播放，而播放回调未被系统投递、且
  native 已去重的 IDLE 报告不再出现，Java 侧没有观察点，回退不会触发。

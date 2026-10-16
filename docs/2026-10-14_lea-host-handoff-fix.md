# 手机 LE Audio 与电脑交接修复

已完成修复代码、JVM 回归测试和真机安装，当前版本为 **2.0.2 / 35**。实机已确认交接 hook 和 ACL 资源释放 hook 加载成功。完整的“手机 LEA → 电脑播放超过 35 秒 → 手机重选恢复 LEA”听音验证仍未完成。

设备为 OPPO Find X9 / PLJ110，Android 17、ColorOS，固件 build `B.46c413f-20895a6-20bdbdd`。LSPosed **核心为 2.2.1 (7912)**，管理器为 2.2.0 (7854)；核心版本以服务启动日志为准。构建使用 JBR / JDK 21。

## 证据与结论

本次仅处理当前仓库、手机蓝牙进程和同一副 AZ100 的手机 / Mac 连接。原始日志、安装前 APK 和已有工作区补丁均保存在 `/tmp/az100-lea-handoff.9VSSFP/`；任务范围见该目录的 `scope.md`，过程见 `timeline.md`。

| E-id | source_ref | 关键证据 | repro_command | content_hash |
| --- | --- | --- | --- | --- |
| E-01 | `az100-before.log`、`adapter-before.txt` | 11:41:39.332 打开让出窗口；39.369 出现 `leaActiveForcedYielded` 后仍有 `groupSwitch.device`；40.026 系统记录 LE Audio active | 下方日志与状态读取命令 | 模块日志 SHA256：`9f91693b5e860ee039791db7c92703c6c7948841c71830a55a74f026f32528d5` |
| E-02 | 当前设备的 `/system/app/Bluetooth/Bluetooth.apk`，ASC 输出 `LeAudioService.java`、`LeAudioStackEvent.java` | 媒体上下文 / blocked media 可直接调用 `setActiveGroupWithDevice(null, false)`；非空 device 激活会调用 native `groupSetAudioResume` | `droidasc getclass Bluetooth.apk 'Lcom/android/bluetooth/le_audio/LeAudioService;'` | APK SHA256：`ad1899cbdf7969d2a279cf447052bd2ea146cb3f29ebcba7f62ade55b9e05813` |
| E-03 | `adapter-repro.txt`、`bluetooth-capture.log`、`mac-handoff.swift` | 手机两耳 Connecting → 30 秒超时 → Disconnected；Mac `openConnection=-536870186`、`connected=false` | 本次一次手机 LE connect 和一次 Mac openConnection；停止重复尝试 | n/a |
| E-04 | `az100-final.log` | `native handoff signals armed`、两个 binder 的 `local selection armed`、`native: patch rc=0` | 重启蓝牙后读取模块日志 | n/a |
| E-05 | `app/build/test-results/testDebugUnitTest/` | 26 项测试，0 failures / errors；交接状态测试 10 项 | `gradle :app:testDebugUnitTest :app:assembleDebug` | n/a |
| E-06 | `adapter-reclaim-reproduced.txt`、`az100-reclaim-reproduced.log` | 12:25:20 两只耳塞 LE ACL 均为 `REMOTE_DEVICE_TERMINATED_CONNECTION_LOW_RESOURCES(0x14)`；模块随后记录 `yieldOnRelease skipped` 和 `A2DP reconnect requested`，再次 `groupActivated=true` | 一次 Mac 连接后读取 AdapterService `--print` 与 `az100.log` | n/a |
| E-07 | `adapter-live.txt`、`az100.log` | 14:25:57 手机播放从 Active 变为 Non-active；14:25:58 本机发起 LocalSource `SUSPEND_REQ` 并 RELEASE ASE。`84:85` 以 `POWER_OFF(0x15)` 离开，没有 `0x14`，也没有模块发起的 A2DP 重连。Mac 只建立 classic 控制链路，没有 AZ100 音频输出设备 | 安装 2.0.2 后读取当时 dump 和 Mac `system_profiler` | n/a |

| F-id | severity | evidence_ids | confidence | location | status |
| --- | --- | --- | --- | --- | --- |
| F-01 | n/a_re | E-01、E-02 | 高：日志证明让出期间仍走原生活跃路径；静态分析证明 native resume 的调用位置 | `OplusDualModeCoexistHook.kt` | 已拦截确认交接后的 group 激活；听音验证待耳机在线 |
| F-02 | n/a_re | E-02、安装前工作区补丁 | 高：连接状态无法表达跨主机媒体归属；旧 guard 持续拒绝已连接 group 的释放，旧强制退出又重连手机 A2DP | `hookLeAudioActiveBypass`、`abandonExclusiveHold`、延迟独占任务 | 已改为状态驱动的让出与恢复；普通断开仍保留 classic fallback |
| F-03 | n/a_re | E-06 | 高：断开原因与模块日志对应 | `LeaHandoffState.onAclDisconnected`、`hookLeaAclRelease` | 已把 LE `0x14` 视为远端资源接管，并阻止交接期间的自动 LE 重连；普通超时和本机主动断开仍走 classic fallback |
| F-04 | n/a_re | E-07 | 中：本次没有形成电脑媒体路径 | Mac classic 连接与手机 LE Audio active 状态 | 未完成。手机播放停止不能当成电脑接管；当前 group 1 仍被标成 active，但 `84:85` 已断开 |

**P-01 / path_type=callflow**：native AUDIO_CONF / group health / autonomous inactive → `LeAudioService.messageFromNative` → 记录 AZ100 的媒体归属 → 放行 group 释放 → 阻止后续自动激活 / native resume、手机 A2DP 重连及输出重设 → native MEDIA 返回或手机明确连接 / 选择耳机时恢复。

## 代码变化

- `LeaHandoffState.kt`：把初次零上下文和曾经可用的 MEDIA 撤回区分开。确认交接后保持让出状态，不因原来的 30 秒窗口到期自行恢复。
- `OplusDualModeCoexistHook.kt`：监听当前 ROM 实际提供的 native 事件；15 秒启动保护仅处理初始状态，真实媒体撤回会覆盖保护。确认交接期间，原生 `setActiveGroupWithDevice(device, ...)` 也被挡住，防止绕过模块 gate 调用 native resume。
- 独占状态退出只清除本地标记，交接期间不建立手机 A2DP；已排队的独占、HFP 清理、LE 输出重设和 A2DP 重试在执行时再检查交接状态。
- LeAudio 和 AdapterService 的 binder 入口标识手机主动连接 / 选择；状态在权限检查后的服务入口恢复。这样系统先选 classic profile 时也可以重新接管 LEA。
- 保留工作区已有的连接状态 gate、冷却时间和双耳补连让出检查。没有更改作用域。

## 构建与检查

运行命令：

```bash
JAVA_HOME='/Users/tosasitill/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home'   gradle :app:testDebugUnitTest :app:assembleDebug --console=plain
git diff --check
```

26 项测试通过：RACE 13 项、自动连接 gate 3 项、交接状态 10 项。新增测试覆盖初始零上下文、最近激活后的电脑接管、保留其他 context 时的 MEDIA 撤回、超过 30 秒的交接保持、native MEDIA 返回、手机主动选择、LE `0x14` 资源释放、自动上下文报告不能取消资源释放，以及普通超时 / 本机断开仍保留 classic fallback。

当前安装的 APK：`app/build/outputs/apk/debug/app-debug.apk`，版本 **2.0.2 / 35**。已用 `adb install -r` 安装并重启蓝牙进程。启动日志包含 `ACL resource-release hook armed`。

## 实机验收步骤

1. 耳机取出并戴上，Mac 先断开耳机。手机通过 LE Audio 连接，确认有声音。
2. 从下面的命令保存基线日志与状态；在电脑上发起 AZ100 连接并播放。
3. 再次保存日志与状态。应能观察到 `MEDIA withdrawn`、`remote handoff` 或 `groupDeactivationYielded`；在确认交接之后，模块不应发起 `A2DP reconnect requested` 或重新强制 `groupActivated=true`。
4. 等待至少 35 秒，确认音频仍在电脑。手机 LE ACL 可以保留；不能只用 `STATE_CONNECTED` 判断交接失败。
5. 在手机上明确选择 AZ100 / 重新连接，确认出现 `handoff ended by local selection` 或 `MEDIA returned`，LE Audio 恢复声音。最终结论以两端实际音频和系统 active / native group 状态为准。

```bash
adb shell "su -c 'cat /data/misc/bluetooth/az100.log'" > /tmp/az100-handoff-check.log
adb shell "su -c 'dumpsys activity service com.android.bluetooth/.btservice.AdapterService --print'"   > /tmp/az100-handoff-check.txt
rg -a -n -e 'currentlyActiveGroupId' -e 'mLeAudioActiveDevice' -e 'available contexts'   /tmp/az100-handoff-check.txt
rg -n -e 'MEDIA withdrawn' -e 'remote handoff' -e 'Yielded'   -e 'A2DP reconnect requested' -e 'groupActivated' /tmp/az100-handoff-check.log
```

Android 17 的文本 profile dump 需要服务级 `--print`；文件内含 NUL 字节，搜索时使用 `rg -a`。

## 当前验证边界

2.0.2 已安装，ACL hook 也已加载。14:25 这次不能计入电脑接管成功：Mac 只连上 classic 控制链路，没有创建 AZ100 音频输出设备；手机先停止本地播放并主动 SUSPEND / RELEASE，耳机以 `0x15` 离开，不是 `0x14` 资源接管。完整听音验证仍需要手机先稳定播放 LE Audio，再由电脑建立并保持媒体输出。

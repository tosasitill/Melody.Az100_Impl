# 2026-10-14：Mac 的 A2DP 媒体通道尚未完成

本报告保留当时手机仍加载 2.0.8 的排查证据。后续 Bluetooth 入口日志已确认 22:14:58.574 实际加载 2.0.9 / 43；用户随后表示当前已无问题，并授权提交推送。本报告中的对照步骤仅作后续回归参考，不再作为本次交付的待办。并行 RFCOMM 与无声之间的因果关系没有完成独立验证。

此前用户确认手机手动断开后不会自行连回，但电脑仍没有音频，看起来只建立 GATT。本轮只读取手机状态、Mac Bluetooth / CoreAudio 日志和系统音频设备，没有发起连接、播放、临时程序或服务重启。

## 已确认的链路与卡点

手机在 21:41:43.848 收到 AZ100 LE ACL 的 `REMOTE_DEVICE_TERMINATED_CONNECTION_LOW_RESOURCES(0x14)`，模块在 21:41:43.882 记录 `remote ACL resource release ... group=1 reason=0x14`；classic ACL 随后在 21:41:44.499 以 `0x13` 断开。

当前 AdapterService dump 的 LE Audio / A2DP / HFP 连接数均为 0，`currentlyActiveGroupId: -1`、`mActiveAudioOutDevice: null`、`mLeAudioActiveDevice: null`。native group 1 的当前状态与目标状态均为 `IDLE`，`cig state: NONE`，`CISes (0)`，所以这个快照没有残留的手机 LE 音频传输。

同一次电脑操作中，Mac 的日志证明连接已经超过 GATT 阶段，但尚未证明媒体通道完成：

| 时间 | 来源 | 事件 |
| --- | --- | --- |
| 21:41:43.713 | bluetoothd / AVDTP | 用户连接请求触发 `AVDTP_Connect` |
| 21:41:44.265–44.266 | bluetoothd | `AVDTP_CONNECT_CFM ... OI_OK`，A2DP Source profile result 0 |
| 21:41:44.400 | bluetoothd | 发现 3 个音频端点 |
| 21:41:44.566 | bluetoothd | 选择 AAC，设置 stream configuration |
| 21:41:44.620 | bluetoothd | 对端确认配置成功，调用 `AVDTP_Open` |
| 21:41:44.677 | bluetoothd | `Received OPEN - ACCEPT`，`CreateMediaTransport(1)`，对 PSM `0x19` 调用 `OI_L2CAP_Connect`，状态 `CONFIGURED -> OPENING` |
| 21:41:44.987 | bluetoothd | 发布 accessory (`taad`) 与 HFP (`tsco`) 对象 |
| 21:41:44.994–44.998 | coreaudiod | 收到 Publish，但 `mA2DPAudioDevice 0x0` |
| 21:41:44.998 | coreaudiod | `AllProfilesConnected service Mask=11 ... Connect Mask=1`，`All profiles not connected, delay publishing` |

加入 `--info --debug` 检查 21:41:44–21:43:00 的 AVDTP / A2DP / L2CAP 日志后，仍未看到该媒体通道的完成记录或 `OPENING -> OPEN`。日志缺失本身不足以证明底层失败原因；当前系统没有 AZ100 输出设备，与未完成的媒体建立状态一致。

`system_profiler SPAudioDataType` 中没有 Technics EAH-AZ100 输出，默认输出仍为内置扬声器。Bluetooth profile 的连接成功与 `AccessoryAudioClient successfully created and published device` 均不能替代最终 CoreAudio 音频输出。后者本次指 accessory 对象，不能误读为 A2DP 媒体输出发布成功。

CoreAudio 的等待是已观察到的下游状态，还不能将根因归到它自身。Apple vendor-specific codec 长度错误之后 AAC 配置仍成功；同窗口出现的 PSM 1 配置超时也不能直接当成 PSM `0x19` 媒体通道失败。

## 下一次对照只改变自动控制读取

在同一次连接中，Mac 菜单栏 AZ100 工具于 21:41:44.270 收到连接通知，21:41:44.330 就通过 `az100ctl` 打开 RFCOMM channel `0x15`，早于 AVDTP 的媒体通道打开阶段。之后工具又在 21:43 左右请求 RFCOMM。

当前源码的路径是 `deviceConnected()` → `syncConnection()` → `refresh(reason: "connect")` → `runHelper(["status", ...])`。它以 Bluetooth 连接通知触发降噪 / 电量读取，没有等待 CoreAudio 输出设备出现。系统日志已确认本轮这类读取确实与 A2DP 建立并行；尚未证明它导致了媒体通道停留在 `OPENING`。

下一步由用户通过正常界面操作：

1. 在 Mac 菜单栏 AZ100 工具中选择“退出”。
2. 在 Mac 系统蓝牙菜单断开 / 重新连接 AZ100 一次，保持手机当前断开状态。
3. 检查系统声音输出中是否出现 Technics EAH-AZ100；若出现，由用户用常用播放器听音。

助手继续用只读日志比较：是否不再有并行的工具 RFCOMM 请求，AVDTP 是否完成 media open，以及 CoreAudio 是否创建 A2DP / 统一输出设备。这个对照完成前不把自动控制读取宣布为根因，也不加入猜测性 hook 或固定延时补丁。

本轮曾查询 Mac 音频服务的管理权限，系统返回 `sudo: a password is required`；没有执行重载。新增 debug 证据后，不把重载 `coreaudiod` 列为首个恢复步骤。

## 独立的版本加载问题

排查快照时，手机 package manager 确认安装包为 2.0.9 / 43，但 Bluetooth 进程 PID 32245 的启动日志仍记录 2.0.8 / 42。LSPosed 注册路径与当前安装 APK 一致，两条 scope 仍正常；当时的问题不是路径或作用域回退。后续进程重新加载已从新的入口日志确认，见本报告开头的交付状态。

开关蓝牙只重启 profile，并不保证进程退出。上一轮关于“正常关 / 开蓝牙就会加载新 hook”的说明不够准确。后续需以目标进程入口日志的实际版本确认，不能只看 APK 版本，也不能把本轮用户断开有效的观察归因于尚未加载的新版 hook。

## 本地证据

原始证据仅保存在 `.git/handoff-repair-20261014/`，不提交设备地址：

- `bluetooth-mac-gatt.txt`、`bluetooth-gatt-final.txt`、`adapter-mac-gatt.txt`、`audio-mac-gatt.txt`
- `mac-state-after-209.txt`、`mac-audio-final-read.txt`
- `mac-bluetooth-gatt-log.txt`、`mac-stream-open-detail.txt`、`mac-stream-open-followup.txt`、`mac-transport-debug.txt`
- `mac-coreaudio-publish-log.txt`、`mac-audio-registration-log.txt`、`mac-menubar-connect-log.txt`

本轮未更改 Android hook 或 Mac 系统配置，未安装新版本。已更新仓库约定，要求核实目标进程实际加载版本。当时电脑出声未验收；新版实际加载后，用户的使用反馈已满足本次交付条件。

# AZ100 的 Airoha 控制帧

这些字节从 Audio Connect 4.4.0 的 `com.airoha.libmmi` 和 `com.panasonic.audioconnect.airoha` 反编译确认。Android 模块在 `com.oplus.melody` 里用自己的 `AirohaRace` 和 `DirectAirohaController` 发送，不加载、不反射 Audio Connect。

macOS 侧的 `macos/az100.sh` 发送同一组帧。

## 连接

Audio Connect 的 `DeviceMmiAiroha2.setConnection` 使用：

- `AirohaLinker(context, 6)`
- SPP UUID：`00000000-0000-0000-0099-AABBCCDDEEFF`
- `H4Transport`

模块仅复用这条链路的帧格式。Android 侧以按次打开的短连接完成一次状态读取或控制序列，序列结束或超时后关闭 SPP，不保留常驻会话。

## 帧格式

MMI 命令是 type `0x5A` 的 RACE 帧：

```text
05 5A <len LE16> <race id LE16> <payload>
```

`len` 含 RACE ID 两字节和 payload 字节数，不含 channel、type 和长度字段；RACE 总帧长是 `4 + len`。因此写 OutsideCtrl 的 3 字节 payload 长度为 `5`：

```text
05 5A 05 00 0B 00 <mode> <noise> <ambient>
```

响应 type 是 `0x5B`；部分数据随后用 `0x5D` 异步通知。Audio Connect 的 `AirohaMmiMgr` 响应超时为 6000 ms；Android 模块也使用 6000 ms RACE 应答超时。

## 降噪

`AirohaMmiMgr.setOutsideCtrl` 入队 `StageSetOutsideCtrl`：

- 读：RACE ID `10`
- 写：RACE ID `11`
- payload：`mode, noiseCancelLevel, ambientLevel`
- 写帧：`05 5A 05 00 0B 00 <mode> <noise> <ambient>`

`OutsideCtrl` 在反编译出的 Audio Connect 模型里默认 `noiseCancelLevel=0`、`ambientLevel=10`；这只是官方模型的字段默认值，不是当前 Android 模块发出的默认值。模块的 `DirectAirohaController` 在尚未收到有效回报时使用 `100/100`，收到耳机报告的 `1..100` 后才更新缓存并沿用。AZ100 对 `0/10` 这类旧默认值可能返回状态 `1`（拒绝），因此不能把反编译模型默认值当成可写参数。响应状态 `0` 表示成功。

音量面板只轮换三个值：

| 面板值 | RACE mode | 含义 |
| --- | --- | --- |
| `5` | `1` | 降噪 |
| `1` | `0` | 关闭 |
| `2` | `2` | 通透 |

自适应是另一条命令：`AirohaMmiMgr.setAdaptiveAnc(byte)`，读 ID `103`，写 ID `104`，AZ100 用 `0`/`1`。它不在磁贴轮换里。耳机当前如果是自适应，面板显示降噪，下一次切换先写 `0`。

## 电量

- 主耳方向：RACE ID `3284`（`0x0CD4`）
- TWS 电量：RACE ID `3286`（`0x0CD6`）
- 充电盒：RACE ID `64`（`0x0040`）

请求帧与响应：

| 用途 | 请求帧 | 响应 |
| --- | --- | --- |
| 主耳方向 | `05 5A 02 00 D4 0C` | `0x5B` 响应偏移 7 为 `0`/`1` |
| 耳机电量 | `05 5A 03 00 D6 0C <role>` | `0x5B` ACK 后 `0x5D` 通知带角色和电量 |
| 充电盒 | `05 5A 02 00 40 00` | `0x5B` 响应偏移 7 为电量 |

长度字段小端，含 2 字节 RACE ID 和 payload；全帧长度为 `4 + length`。

电量通知的角色 `0`/`1` 是 agent/partner，**不等于固定的左右耳**，所以先查主耳方向再映射。
`3286` 的 ACK 和电量本体经常落在同一个 RFCOMM 包里，解析时不能因为等 ACK 丢掉另一帧。
值大于 100 按未知处理。

Android 模块先读主耳方向，再依次请求 TWS role `0` 和 `1`。每个请求先等待 `0x5B` ACK，再最多等待 6000 ms 的匹配 `0x5D` 电量通知；通知缺失时保留已有缓存，无缓存则仍为未知，并继续后续请求，不保证一定收到左右耳数据后才关闭 SPP。最后读取充电盒并在本轮命令序列结束后关闭短连接。只有收到对应数据时才更新该侧缓存，超过 100 的上报值会将该侧置为未知。Mac 工具则按自己的命令超时收集通知。

## LE Audio 固件开关

`DeviceMmiAiroha3` 和 `LeAudioSettingFragmentZui` 里只有两个值：

| 常量 | 值 |
| --- | --- |
| `LE_AUDIO_SETTING_OFF` | `0` |
| `LE_AUDIO_SETTING_ON` | `1` |

- 读：`StageGetLeAudioSetting`，RACE ID `89`
- 写：`StageSetLeAudioSetting`，RACE ID `90`，payload 一个字节

官方 App 写完后进入重新选择设备的界面，耳机断开再重连。这里没有第三个 “LE-only” 值，也没有承诺写 `1` 后 classic A2DP/HFP 会消失。

这条命令改耳机固件。手机是否把 LE Audio 当成 active、系统设置是否显示开关，由 `com.android.bluetooth` 里的钩子决定，见 `README.md`。

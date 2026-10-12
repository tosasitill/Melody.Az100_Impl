# AZ100 的 Airoha 控制帧

这些字节从 Audio Connect 4.4.0 的 `com.airoha.libmmi` 和 `com.panasonic.audioconnect.airoha` 反编译确认。Android 模块在 `com.oplus.melody` 里用自己的 `AirohaRace` 和 `DirectAirohaController` 发送，不加载、不反射 Audio Connect。

macOS 侧的 `macos/az100.sh` 发送同一组帧。

## 连接

Audio Connect 的 `DeviceMmiAiroha2.setConnection` 使用：

- `AirohaLinker(context, 6)`
- SPP UUID：`00000000-0000-0000-0099-AABBCCDDEEFF`
- `H4Transport`

模块只复用这条链路的帧格式。SPP 按次打开，命令完成或超时后关闭，不留常驻连接。

## 帧格式

MMI 命令是 type `0x5A` 的 RACE 帧：

```text
05 5A <len LE16> <race id LE16> <payload>
```

`len` 含 race id 和 payload。响应 type 是 `0x5B`；部分数据随后用 `0x5D` 异步通知。`AirohaMmiMgr` 的响应超时是 6000 ms。

## 降噪

`AirohaMmiMgr.setOutsideCtrl` 入队 `StageSetOutsideCtrl`：

- 读：RACE ID `10`
- 写：RACE ID `11`
- payload：`mode, noiseCancelLevel, ambientLevel`
- 写帧：`05 5A 03 00 0B 00 <mode> <noise> <ambient>`

`OutsideCtrl` 默认 `noiseCancelLevel=0`、`ambientLevel=10`。响应状态 `0` 表示成功。

音量面板只轮换三个值：

| 面板值 | RACE mode | 含义 |
| --- | --- | --- |
| `5` | `1` | 降噪 |
| `1` | `0` | 关闭 |
| `2` | `2` | 通透 |

自适应是另一条命令：`AirohaMmiMgr.setAdaptiveAnc(byte)`，读 ID `103`，写 ID `104`，AZ100 用 `0`/`1`。它不在磁贴轮换里。耳机当前如果是自适应，面板显示降噪，下一次切换先写 `0`。

## 电量

- 主耳方向：RACE ID `3284`
- TWS 电量：RACE ID `3286`
- 充电盒：RACE ID `64`

`3286` 先回 `0x5B` ACK，电量本体在随后的 `0x5D`。两次读经常落在同一个 RFCOMM 包里，解析时不能因为等 ACK 丢掉另一帧。

从耳可能 5–10 秒才上报，也可能不上报。大于 100 的值按未知处理。刷新时先拿主耳方向，再等 ACK 和异步通知，两边都到了才关 SPP。

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

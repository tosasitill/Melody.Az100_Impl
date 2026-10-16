# macOS 侧工具

在 Mac 上查看 / 切换 Technics EAH-AZ100 的降噪模式和电量。协议实现与 Android
模块同源，使用 Airoha SPP/RACE（`05 5A <len LE16> <race id LE16> <payload>`）；Android 侧实现位于 `AirohaRace.kt` / `DirectAirohaController.kt`，macOS 命令行版本则将 Swift 协议客户端内嵌在 `az100.sh` 中。

| 文件 | 作用 |
| --- | --- |
| `az100.sh` | 命令行工具。自包含，内嵌协议实现的 Swift 源码 |
| `az100-menubar.swift` | 菜单栏 App 源码 |
| `az100-menubar.sh` | 构建 / 安装 / 开关菜单栏 App |

## 依赖

只需要 Xcode Command Line Tools 里的 `swiftc`：

```bash
xcode-select --install
```

macOS 不向 shell 暴露 RFCOMM socket，所以两边都用 `IOBluetooth.framework`。
`az100.sh` 首次运行会把内嵌的 Swift 编译到 `~/.cache/az100/az100ctl`；
菜单栏 App 构建时从 `az100.sh` 里抽出同一份源码，编进 App bundle。

## 命令行

```bash
./az100.sh                      # 模式 + 电量
./az100.sh anc                  # 降噪（也认 通透 / 关闭 / 自适应 / toggle）
./az100.sh mode                 # 只看模式
./az100.sh battery              # 只看电量
./az100.sh leaudio              # 查看 LE Audio（LC3）开关
./az100.sh leaudio on           # 打开 LE Audio（也认 off / 开启 / 关闭）
./az100.sh watch 5              # 持续刷新
./az100.sh devices              # 列出已配对设备
./az100.sh raw 05 5A 02 00 0A 00   # 直接发帧看回包
./az100.sh -j status            # KEY=VALUE，方便脚本消费
./az100.sh -v mode              # 打印每一帧原始字节
```

### LE Audio / LC3

走 Airoha `RaceIdPana` 的 `GET_LE_AUDIO = 89` / `SET_LE_AUDIO = 90`
（`SET` 带 1 字节负载：0 = 关，1 = 开）。本机实测可用：

```
$ ./az100.sh leaudio
LE Audio    开（LE Audio / LC3）

$ ./az100.sh -j leaudio
LE_AUDIO=1
```

这改的是耳机固件。手机侧是否走 LE Audio、系统设置里有没有开关，由 LSPosed 模块在
`com.android.bluetooth` 里决定，见仓库根目录的 `README.md`。官方只有开和关，没有
单独的 LE-only 模式；写 `1` 不保证 classic A2DP/HFP 消失。切换时耳机会断开重连一次，
脚本在 `-j` 之外会提示等重连后复核。

固定设备（多副耳机时）：

```bash
mkdir -p ~/.config/az100 && echo 'AZ100_MAC=AA:BB:CC:DD:EE:FF' > ~/.config/az100/config
```

## 菜单栏

```bash
./az100-menubar.sh              # 构建 + 安装到 ~/Applications + 启动
./az100-menubar.sh status       # 看是否在跑
./az100-menubar.sh stop         # 停止
./az100-menubar.sh login        # 设为登录自启
./az100-menubar.sh uninstall    # 全部移除
```

耳机连上这台 Mac 时，菜单栏出现「降噪 99%」这样的条目（数字取较弱的那只耳，
两只都不上报时显示充电盒）；点开可以切换四种模式、看左右耳和充电盒电量、
开关 LE Audio（LC3）、手动刷新。耳机一断开，条目自动消失，期间不会去连耳机。

### 状态侦测与周期动作

连接状态通常由 IOBluetooth 通知（`registerForConnectNotifications` /
`registerForDisconnectNotification`）驱动；经典蓝牙连接的通知并不始终可靠，因此当前 App 还每 5 分钟
通过**子进程**运行一次 `az100ctl devices` 对账。静置功耗测试结果不能从旧版照搬到当前实现。

> 为什么不能轮询：`IOBluetoothDevice` 在**同一个进程内是单例**（头文件原话：
> "Within a single application, there will be only one instance of IOBluetoothDevice
> for a given remote device address"），而它的 `isConnected()` **一旦为 true 就再也
> 不会回到 false**。实测把 Mac 蓝牙整个关掉 30 秒，长驻进程里读到的还是 true，
> 只有新起的进程才报 false。这也是不应在长驻进程里反复读此属性的原因；当前用子进程对账取得新的连接状态。
>
> 另外 `IOBluetoothDevice.pairedDevices()` 在蓝牙关闭时会**卡住不返回**，
> 所以它也只用于启动时解析一次地址，绝不放进周期路径。

除启动时探测外，当前还有两个周期动作：每 120 秒刷新一次电量（会按需打开 RFCOMM），每 5 分钟用**子进程**运行 `az100ctl devices` 对账，以补漏连接通知。

App **不持有任何电源断言**（`pmset -g assertions` 里查不到本进程），不会阻止
系统空闲睡眠；系统睡了它也跟着停。
注：`coreaudiod` 会为耳机的 A2DP 输出持有一条 `PreventUserIdleSystemSleep`，
那是音频栈的行为，跟这个 App 无关。

## 已知的 macOS 坑

- 一个进程里**第一次** `openRFCOMMChannelAsync` 永远不会回调（等 30 秒也不回，
  同步版 3.1 秒后返回 `kIOReturnError`），第二次必定成功。所以第一次只给 0.5 秒
  就放弃重试。打开后还要显式 `channel.setDelegate(self)`。
- 帧解析必须保留全部帧。TWS 电量的 `0x5B` ACK 和 `0x5D` 通知经常挤在同一次
  RFCOMM 读里到达，等待 ACK 时若丢掉不匹配的帧，电量就永久丢了。
- 从耳（role 1）的电量通知常常要 5～10 秒才来，有时整轮不来；值偶尔是超范围的
  原始字节（实测 `0xC0`）。官方 App 也是原样上报，模块里按 `>100 → 未知` 处理。
- 菜单栏 App 是独立签名的 bundle，首次运行会单独弹一次蓝牙权限请求
  （不再借用终端的权限）。重新构建后是同一个临时签名，不会重复弹。
- **`IOBluetoothUserNotification` 的回调不在主线程**，而是投递到 dispatch
  worker 线程。在里面直接碰 AppKit（建 `NSStatusItem` 等）会抛
  `NSWindow should only be instantiated on the main thread!` 并崩掉。
  回调里第一件事必须是 `DispatchQueue.main.async`。
- `registerForConnectNotifications` 有时会为已连接的设备补发回调，但经典蓝牙通知不总可靠；启动时仍会安排探测，之后由子进程对账兜底。

## 环境变量

| 变量 | 作用 |
| --- | --- |
| `AZ100_MAC` | 固定监听哪台设备（默认按名字匹配 AZ100 / AZ-100 / EAH- / TECHNICS） |
| `AZ100_ALWAYS_SHOW` | 设为 `1` 时耳机没连也保留菜单栏条目（调试用） |
| `AZ100_INSTALL_DIR` | `az100-menubar.sh` 的安装目录，默认 `~/Applications` |

`az100.sh` 还会读 `~/.config/az100/config` 里的 `AZ100_MAC`（优先级低于命令行）。

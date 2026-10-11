# macOS 侧工具

在 Mac 上查看 / 切换 Technics EAH-AZ100 的降噪模式和电量。协议实现与 Android
模块同源：都走 Airoha SPP/RACE（`05 5A <len LE16> <race id LE16> <payload>`），
代码从 `AirohaRace.java` / `DirectAirohaController.java` 移植而来。

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

**注意这会改耳机固件侧的状态**，与手机端 Melody / 系统设置里的 LE Audio 开关是
两条独立链路：这边改耳机，那边改手机 UI 是否放行（详见仓库根目录
`2026-10-11_leaudio-whitelist-report.md`）。切换时耳机会断开重连一次，
所以脚本在 `-j` 之外会提示「等耳机重连后复核」。

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

### 功耗：没有轮询

连接状态**完全由 IOBluetooth 通知驱动**（`registerForConnectNotifications` /
`registerForDisconnectNotification`），没有任何定时轮询。静置实测
**0.25 次唤醒/秒、0.00 mJ**（`proc_pid_rusage`，20 秒窗口）。

> 为什么不能轮询：`IOBluetoothDevice` 在**同一个进程内是单例**（头文件原话：
> "Within a single application, there will be only one instance of IOBluetoothDevice
> for a given remote device address"），而它的 `isConnected()` **一旦为 true 就再也
> 不会回到 false**。实测把 Mac 蓝牙整个关掉 30 秒，长驻进程里读到的还是 true，
> 只有新起的进程才报 false。所以一开始那个 3 秒轮询既不准、还白耗唤醒。
>
> 另外 `IOBluetoothDevice.pairedDevices()` 在蓝牙关闭时会**卡住不返回**，
> 所以它也只用于启动时解析一次地址，绝不放进周期路径。

只有两个额外动作：每 120 秒刷新一次电量（会开一次 RFCOMM），以及每 5 分钟
用**子进程**跑一次 `az100ctl devices` 对账——子进程是唯一还能看到真实连接状态
的地方，用来兜住漏掉的通知。

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
- `registerForConnectNotifications` 在注册时就会为**已连接**的设备补发一次
  回调，所以启动时不需要额外探测；但两个都保留更稳。

## 环境变量

| 变量 | 作用 |
| --- | --- |
| `AZ100_MAC` | 固定监听哪台设备（默认按名字匹配 AZ100 / AZ-100 / EAH- / TECHNICS） |
| `AZ100_ALWAYS_SHOW` | 设为 `1` 时耳机没连也保留菜单栏条目（调试用） |
| `AZ100_INSTALL_DIR` | `az100-menubar.sh` 的安装目录，默认 `~/Applications` |

`az100.sh` 还会读 `~/.config/az100/config` 里的 `AZ100_MAC`（优先级低于命令行）。

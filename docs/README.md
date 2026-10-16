# 文档索引

`README.md` 是当前运行行为的唯一权威；这里的文件是**带日期的证据记录**，描述的是
当时的状态，结论可能已经被后续修复取代。两者冲突时以 `README.md` 为准。

## 协议

| 文件 | 内容 |
| --- | --- |
| [audio-connect.md](audio-connect.md) | Airoha SPP/RACE 控制帧格式：降噪、电量、LE Audio 固件开关。Android 模块和 macOS 工具共用这套帧 |

## 逆向与排查记录

按时间倒序。这些记录的价值主要在**逆向证据**：固件升级后偏移会变，需要重新核对时
它们能说明当初是怎么找到的。

| 文件 | 内容 |
| --- | --- |
| [2026-10-16_media-volume-curve.md](2026-10-16_media-volume-curve.md) | 修复服务实例捕获依赖实验控制台的问题；新增媒体音量平方曲线和反向同步转换，实机听感待验证 |
| [2026-10-14_modern-hook-and-audio-fix.md](2026-10-14_modern-hook-and-audio-fix.md) | 接回 libxposed API 102 现代入口；修复 LE Audio 连接初始化误判交接、厂商 HFP 初始化清空输出 |
| [2026-10-14_manual-disconnect-and-handoff-fix.md](2026-10-14_manual-disconnect-and-handoff-fix.md) | 手动断开后自动连回、以及手机连接时电脑无法接管的修复过程与时间线 |
| [2026-10-14_mac-audio-publish-stall.md](2026-10-14_mac-audio-publish-stall.md) | Mac 侧 A2DP 媒体通道停在 OPENING、CoreAudio 不发布输出的证据快照（因果关系未证实） |
| [2026-10-14_lea-host-handoff-fix.md](2026-10-14_lea-host-handoff-fix.md) | 手机 LE Audio 与电脑交接的初版修复，HCI `0x14` 接管语义 |
| [2026-10-12_native-oppo-leaudio-masquerade-report.md](2026-10-12_native-oppo-leaudio-masquerade-report.md) | ColorOS 原生 LE Audio 判定链路的逆向：厂商/产品 ID 加白名单，地址表无效；`bt_config.conf` 路线为何打不开门 |
| [2026-10-11_leaudio-whitelist-report.md](2026-10-11_leaudio-whitelist-report.md) | 系统设置里 LE Audio 开关的出现条件：`0000184e` 与 `LeAudioProfile` 进 `mProfiles` 的两条路径 |

## scripts/

[scripts/](scripts/) 下是**已废弃**的 `bt_config.conf` 改配置路线脚本，保留作为
失败路径的记录。说明见 [scripts/README.md](scripts/README.md)。

## 删除的记录

- `2026-10-03_reverse-az100-battery-report.md`：电量刷新协议修复记录。帧格式、
  超时和左右耳映射已全部并入 [audio-connect.md](audio-connect.md)。
- `2026-10-11_az100-leaudio-v159-summary.md`：v1.5.9 会话总结。当时是 Java 实现，
  现已重写为 Kotlin，音量下限、autonomous 回报保护、双耳补连的机制都写在
  `LeAudioTransportHook.kt` 的注释里。

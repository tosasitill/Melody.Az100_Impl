# 历史脚本：bt_config.conf 改配置路线（已废弃）

这些脚本属于**已经放弃的路线**：通过改 `bt_config.conf` 的
`broadcastVendorId` / `broadcastProductId`，让 AZ100 在 native 层被当成 OPPO
自家 LE Audio 设备。

它打不开门。native 的判定除了厂商/产品 ID，还要求
`customLeaBitWritten == 1` —— 这个键在 `libbluetooth_jni.so` 和
`liboplus_bt.so` 里都没有写入方 —— 且 (vendor, product) 必须在
`libbluetooth_jni.so` 的 `.rodata` 硬编码白名单里，那份表只有 OPPO 自己的产品 ID。
地址键白名单（`[OplusLeAudioWhiteList]`）同样无效：native 门按厂商/产品查，不按地址查。

当前生效的是进程内原生补丁（按地址判定）加 UUID 注入和 `connectLeAudio()` 直连，
见仓库根目录的 `README.md`。逆向结论见
[伪装成原生 OPPO 耳机的排查记录](../2026-10-12_native-oppo-leaudio-masquerade-report.md)。

保留这些脚本是因为它们记录了当时的失败路径和 `bt_config.conf` 的读写方式
（尤其是「改文件必须在适配器关闭、蓝牙进程已退出时进行」这一条 —— `btif_config`
的进程内缓存会在下一次 flush 时把改动盖回去），固件升级后重新排查时可能还有参考价值。

| 脚本 | 作用 |
| --- | --- |
| `patch-lea-vidpid.sh` | 宿主侧入口，`patch` / `check` / `revert` 三个子命令 |
| `patch-lea-vidpid.device.sh` | 设备侧执行：停适配器 → 杀进程 → 改文件 → 启适配器 |
| `revert-lea-config.device.sh` | 完整撤销：移除 vid/pid 键和白名单地址条目 |
| `open-lea-gate.device.sh` | 重写 `persist.bluetooth.leaudio.bypass_allow_list`（幂等，无效） |
| `read-lea-props.device.sh` | 以 root 读 LE Audio 相关属性；shell 域读会被 property_contexts 挡住 |

`.device.sh` 结尾的脚本在设备上以 root 运行，其余在宿主机运行。

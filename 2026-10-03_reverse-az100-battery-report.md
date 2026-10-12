# AZ100 电量刷新协议修复记录

> 这是 2026-10-03 的协议修复记录。当前的刷新顺序和帧格式见 `docs-audio-connect.md` 与 `README.md`。

## 范围与状态

本轮只修复仓库的电量读取链路并构建、安装模块。不启动 Melody，不通过 ADB 点击
页面，不代替用户做真机功能验证。当前状态为补丁已安装、等待用户验证，不宣称
已经实测取得耳机电量。`AGENTS.md` 保持原样。

## Evidence

| ID | source_ref / repro_command | 观察 | content_hash |
| --- | --- | --- | --- |
| E-001 | `/tmp/az100-device-after-user.log`；`cat /tmp/az100-device-after-user.log` | RFCOMM 已连接；每条命令约 1 秒后报无响应，约 3 秒后主动关闭；没有捕获到 ACK | `d36ee84c1428fc1d29a036ca67c8db0115625237e65cd21d787f11268893878c` |
| E-002 | `reverse/jadx_audio/AC_com_airoha_libmmi_stage_MmiStageTwsGetBattery.java`；`cat` 同路径 | 官方电量阶段先接收 `0x5B`，再继续接收 `0x5D`，角色和电量在偏移 7、8 | `adf3c08de25f4122d88f0b1a4b4804e76bc62f91adb9b05d37d67586121c4c97` |
| E-003 | `reverse/jadx_audio/AC_com_airoha_libmmi_stage_MmiStageCheckAgentChannel.java`；`cat` 同路径 | 命令 3284 查询主耳左右方向，响应偏移 7 为方向；电量角色 0/1 不等于固定左右耳 | `c1e08f7ec4f9c395d7e9b393dc00dd8475678c8dedc909098237ccfb720118b7` |

`reverse/` 是本地分析产物，不纳入 Git；上表命令需要当前工作区保留这些文件。
E-001 证明过早超时，不能证明耳机已经收到请求或本轮补丁必然解决所有无回包情况。

## Findings 与修复

- **F-001**：`severity=n/a_re`，`evidence_ids=E-001,E-002`，`confidence=high`，
  `location=DirectAirohaController.sendAndWait/queryTwsBattery`，`status=validated`。
  已确认代码仅等待 1 秒，且没有等待第二阶段电量通知。改为 6 秒 ACK 窗口及独立
  6 秒通知窗口；严格匹配响应 ID/类型，用角色更新计数处理 ACK 与通知合并收包。
- **F-002**：`severity=n/a_re`，`evidence_ids=E-003`，`confidence=high`，
  `location=DirectAirohaController.queryBattery/onFrame`，`status=validated`。
  原代码把主/从耳角色直接当左右耳。每次刷新先查询主耳方向，再映射左右电量。
- 连接后保留 100ms 稳定窗口；断开时唤醒等待线程；旧 socket 不再投递帧。
  增加 `rx raw`，区分无回包、帧解析失败、ACK 成功但通知超时。以上代码修复已完成，
  真机效果仍待用户确认。

## Path：P-001（path_type=callflow）

`立即刷新电量 → refreshBattery → RFCOMM → 查询主耳方向(E-003/F-002)
→ 角色 0 请求/ACK/通知 → 角色 1 请求/ACK/通知(E-002/F-001)
→ 充电盒响应 → 关闭连接 → 更新页面`。

| 消息 | ID | 请求 / 响应要点 |
| --- | --- | --- |
| 主耳方向 | 3284 (`0x0CD4`) | `05 5A 02 00 D4 0C`；响应 `0x5B` 的偏移 7 为 0/1 |
| 耳机电量 | 3286 (`0x0CD6`) | `05 5A 03 00 D6 0C 00`，另一角色末字节为 01；`0x5B` ACK 后 `0x5D` 带角色/电量 |
| 充电盒 | 64 (`0x0040`) | `05 5A 02 00 40 00`；`0x5B` 响应偏移 7 为电量 |

长度字段为小端，包含 2 字节 ID 和 payload；全帧长度为 `4 + length`。

## 构建、安装与用户验证

```bash
env JAVA_HOME="$(/usr/libexec/java_home -v 21)" \
  PATH="$(/usr/libexec/java_home -v 21)/bin:$PATH" \
  gradle :app:assembleDebug :app:testDebugUnitTest
git diff --check
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.oplus.melody
```

构建成功。`testDebugUnitTest` 为 `NO-SOURCE`，不代表已运行单元测试。
APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

用户保持 AZ100 连接，重新长按进入详情页，点击一次“立即刷新电量”，等待结束并
确认左右耳数值；未报告的电量保持 `--`。若仍失败，可读取日志：

```bash
adb shell cat /sdcard/Android/data/com.oplus.melody/files/az100.log
```

## Timeline

2026-10-03：核对已有失败日志 → 对照官方协议 → 修复两阶段等待及左右映射
→ 本地构建成功 → 安装模块并停止旧 Melody 进程 → 等待用户验证。

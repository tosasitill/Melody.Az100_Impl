# AZ100 媒体音量曲线（2.0.12 / 46）

用户反馈设备同步音量开启后，20% 滑块仍过响。上一版只绕过系统的 VCS=16 下限，没有修改整个媒体音量映射。

只读系统快照中，`mHasBlueVolSync:true`，AudioService 持续记录 SystemUI 的 `setStreamVolume` 和 `setLeAudioVolume`，证明滑块操作进入 LE Audio 同步链。诊断关闭，`az100.log` 仍为旧记录；不能据此确认本次进程加载版本或音量写入是否成功。

本次修改：

- 在 `VolumeControlNativeInterface#setVolume/setGroupVolume` 最后 Java 边界，对 AZ100 且 VCS 当前上下文为 STREAM_MUSIC 的值执行 `round(logical² / 255)`，非零最小值为 1，静音为 0，最大值为 255。
- 20%（51）→10，50%（128）→64，80%（204）→163，100%→255。这是协议值转换，不是主观响度标定。
- `handleVolumeControlChanged` 先以原始值记录写入去重状态，再把音量反向转换为 `round(sqrt(actual * 255))` 交给服务缓存和 AudioManager，避免耳机上报造成重复衰减。
- 继续由“音量修复”开关控制；上下文识别失败时保留旧下限绕过逻辑。
- 保留 2.0.11 的服务生命周期捕获修复，实验控制台继续关闭。

验证：56 个 JVM 测试通过（原有 53 个 + 曲线 3 个），debug 构建成功，ADB 覆盖安装成功。新增测试覆盖静音、最小值、最大值、20%/50%锚点、全部取值的单调性和反向转换不超过 1 个 VCS 单位的量化误差。未远程触发播放、调音量或重启进程。

设备验证仍待用户使用普通应用完成：加载 2.0.12/46 后，开启详细日志，低音量逐步调整，核对 `vcs: write` 中的 `logical`、`mediaCurve=true` 以及 `written straight to native`，并核对耳机回报。Native 返回只证明提交，听感、双耳一致性和设备端实际应用情况尚未验证。

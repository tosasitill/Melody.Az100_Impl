# Technics EAH-AZ100 音量面板适配

LSPosed 模块，只注入 `com.oplus.melody`。音量二级菜单的降噪磁贴由 SystemUI 通过
`content://com.oplus.melody.provider.EarphoneControlProvider` 读取，模块在 Melody
进程内应答 query/call，并用自带的 Airoha SPP/RACE 协议下发模式。SystemUI 和
设备空间都不注入。

不加载、不反射 `com.panasonic.technicsaudioconnect`。Audio Connect 可以卸载。

长按磁贴会进入 Melody 的 AZ100 耳机详情页。模块保留 Activity/工具栏，跳过不支持
AZ100 的原生产品加载，提供连接状态、左右耳和充电盒电量，以及“立即刷新电量”按钮。
刷新时先读取主耳方向，再等待电量请求的 ACK 和异步通知，收到数据后才关闭 SPP。

## 构建

```bash
gradle :app:assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。

## LSPosed

1. 安装并启用模块。
2. 作用域只勾 `com.oplus.melody`。不要勾 `com.heytap.mydevices` 或
   `com.android.systemui`。
3. `adb shell am force-stop com.oplus.melody`。

日志：

```bash
adb logcat -s AZ100:V
```

## 代码

| 文件 | 作用 |
| --- | --- |
| `Az100Hook` | 只在 `com.oplus.melody` 加载，转给 `MelodyProviderHook` |
| `MelodyProviderHook` | 应答音量面板的 active/noise query 和点击 |
| `MelodyDetailBatteryHook` | 在 Melody 耳机详情页提供手动电量刷新 |
| `DirectAirohaController` | 按次打开 SPP，发完即关 |
| `AirohaRace` | H4/RACE 分帧 |

面板模式：`5` 降噪、`1` 关闭、`2` 通透。自适应不出现在轮换里，耳机若处于自适应，
显示为降噪，下一次切换会先清掉。

耳机连上后最多读一次当前模式和电量。音量面板之后的 query 只回内存里的模式，
不会因为 SystemUI 的媒体路由回调反复建立 SPP。

// AZ100 — macOS menu bar companion for the Technics EAH-AZ100.
//
// Shows the current noise-cancelling mode and battery in the menu bar while the
// headset is connected to this Mac, and removes the item entirely when it is not.
// Clicking it opens a menu with the four modes (降噪 / 通透 / 关闭 / 自适应) plus a
// manual refresh.
//
// All protocol work is done by the `az100ctl` helper that lives next to this
// binary in the bundle (it is the same code the CLI wrapper compiles).  This
// process only needs IOBluetooth for one cheap thing: asking whether the headset
// is connected, which it polls every few seconds.  RFCOMM is never opened on a
// timer -- only on connect and on demand -- so the headset is not paged needlessly.

import AppKit
import IOBluetooth

/// Backstop for connection state.  The IOBluetooth connect notification is
/// CoreBluetooth-backed (`centralManager:connectionEventDidOccur:`) and did *not*
/// fire for a classic-only connection during testing, so it is treated as a
/// latency bonus rather than the source of truth.  A 5 s poll costs about
/// 0.01 mJ/min (measured with proc_pid_rusage), which is far below anything that
/// matters -- correctness beats saving wakeups that were never a problem.
private let reconcileInterval: TimeInterval = 300
private let autoRefreshInterval: TimeInterval = 120
private let helperTimeout = "4"

// MARK: - state

private struct Az100State {
    var name = ""
    var address = ""
    var mode = "unknown"
    var modeByte = -1
    var ancLevel = -1
    var ambientLevel = -1
    var adaptive = 0
    var left = -1
    var right = -1
    var cradle = -1
    var leftRaw = -1
    var rightRaw = -1
    var cradleRaw = -1
    var agent = ""
    /// LE Audio (LC3) switch: 1 on, 0 off, -1 the headset did not say.
    var leAudio = -1

    init?(output: String) {
        var fields: [String: String] = [:]
        for line in output.split(separator: "\n") {
            guard let separator = line.firstIndex(of: "=") else { continue }
            let key = String(line[line.startIndex..<separator])
            let value = String(line[line.index(after: separator)...])
            fields[key] = value
        }
        guard let mode = fields["MODE"], !mode.isEmpty else { return nil }

        self.mode = mode
        name = fields["DEVICE_NAME"] ?? ""
        address = fields["ADDRESS"] ?? ""
        agent = fields["AGENT"] ?? ""
        modeByte = Int(fields["MODE_BYTE"] ?? "") ?? -1
        ancLevel = Int(fields["ANC_LEVEL"] ?? "") ?? -1
        ambientLevel = Int(fields["AMBIENT_LEVEL"] ?? "") ?? -1
        adaptive = Int(fields["ADAPTIVE"] ?? "") ?? 0
        left = Int(fields["LEFT"] ?? "") ?? -1
        right = Int(fields["RIGHT"] ?? "") ?? -1
        cradle = Int(fields["CASE"] ?? "") ?? -1
        leftRaw = Int(fields["LEFT_RAW"] ?? "") ?? -1
        rightRaw = Int(fields["RIGHT_RAW"] ?? "") ?? -1
        cradleRaw = Int(fields["CASE_RAW"] ?? "") ?? -1
        leAudio = Int(fields["LE_AUDIO"] ?? "") ?? -1
    }

    /// The number worth putting in the menu bar: the weaker earbud, so the item
    /// warns before the pair dies.  Falls back to the case when both buds are away.
    var headlineBattery: (value: Int, isCase: Bool) {
        let buds = [left, right].filter { $0 >= 0 }
        if let lowest = buds.min() { return (lowest, false) }
        if cradle >= 0 { return (cradle, true) }
        return (-1, false)
    }
}

/// The `leaudio` verb answers with its own keys and no MODE, so it cannot go
/// through Az100State.  `dropped` means the headset tore the link down to
/// renegotiate -- expected, and not a failure.
private struct LeAudioResult {
    var value = -1
    var dropped = false

    init(output: String) {
        var fields: [String: String] = [:]
        for line in output.split(separator: "\n") {
            guard let separator = line.firstIndex(of: "=") else { continue }
            fields[String(line[line.startIndex..<separator])] =
                String(line[line.index(after: separator)...])
        }
        value = Int(fields["LE_AUDIO"] ?? "") ?? -1
        dropped = (Int(fields["LE_AUDIO_LINK_DROPPED"] ?? "") ?? 0) != 0
    }
}

private func leAudioLabel(_ value: Int) -> String {
    switch value {
    case 1: return "开"
    case 0: return "关"
    default: return "--"
    }
}

private func modeLabel(_ mode: String) -> String {
    switch mode {
    case "anc": return "降噪"
    case "ambient": return "通透"
    case "off": return "关闭"
    case "adaptive": return "自适应"
    default: return "未知"
    }
}

private func batteryLabel(_ value: Int) -> String {
    value >= 0 ? "\(value)%" : "--"
}

/// Diagnostics land in the unified log: `log stream --predicate 'process == "AZ100"'`
private func note(_ message: String) {
    NSLog("[AZ100] %@", message)
}

// MARK: - app

final class AppDelegate: NSObject, NSApplicationDelegate {
    private var statusItem: NSStatusItem?
    private let menu = NSMenu()
    private var device: IOBluetoothDevice?
    private var state: Az100State?
    private var lastError: String?
    private var connected = false
    private var busy = false
    private var lastAttempt = Date.distantPast
    private var connectNotification: IOBluetoothUserNotification?
    private var disconnectNotification: IOBluetoothUserNotification?
    /// Serial queue: one probe at a time, and never on the main thread.
    private let probeQueue = DispatchQueue(label: "com.tosasitill.az100.probe")
    private var probeInFlight = false
    private var probeGeneration = 0
    private var reconcileInFlight = false
    /// Resolved once, then reused: the poll must not call pairedDevices().
    private var cachedAddress: String?
    private var pollTimer: Timer?
    private var refreshTimer: Timer?

    private let alwaysShow = ProcessInfo.processInfo.environment["AZ100_ALWAYS_SHOW"] == "1"
    private let preferredAddress = ProcessInfo.processInfo.environment["AZ100_MAC"]

    private var helperURL: URL? {
        Bundle.main.resourceURL?.appendingPathComponent("az100ctl")
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)

        // Connection changes usually arrive as IOBluetooth notifications, which
        // makes the headset appear the moment it connects.  They are not reliable
        // for classic-only links though, and isConnected() cannot be polled at
        // all, so a child-process reconcile runs every few minutes as a safety net.
        connectNotification = IOBluetoothDevice.register(
            forConnectNotifications: self, selector: #selector(deviceConnected(_:device:)))
        note("connect notification registered: \(connectNotification != nil)")

        // isConnected() is cached per process and never returns to false once the
        // link drops, so polling it is both wrong and pointless.  Notifications
        // drive everything; this only reconciles, and it does so from a *child
        // process* -- the only place that still sees the true link state.
        let poll = Timer(timeInterval: reconcileInterval, repeats: true) { [weak self] _ in
            self?.scheduleReconcile()
        }
        RunLoop.main.add(poll, forMode: .common)
        pollTimer = poll

        let refresh = Timer(timeInterval: autoRefreshInterval, repeats: true) { [weak self] _ in
            guard let self, self.connected, !self.busy else { return }
            self.refresh(reason: "auto")
        }
        RunLoop.main.add(refresh, forMode: .common)
        refreshTimer = refresh

        scheduleProbe(forceRefreshOnConnect: true)
        // The launch probe depends on pairedDevices(), which is not always
        // cooperative; a reconcile from a child process is the reliable answer.
        DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self] in
            self?.scheduleReconcile()
        }
    }

    func applicationWillTerminate(_ notification: Notification) {
        pollTimer?.invalidate()
        refreshTimer?.invalidate()
        connectNotification?.unregister()
        disconnectNotification?.unregister()
    }

    // MARK: connection tracking

    // IOBluetoothUserNotification callbacks arrive on a dispatch worker thread,
    // never on the main thread -- touching AppKit from there throws
    // "NSWindow should only be instantiated on the main thread!".  Hop first.
    // It also invokes the selector on a *failed* connection attempt, with a nil
    // device, so the device is unwrapped before anything touches it.
    @objc private func deviceConnected(_ notification: IOBluetoothUserNotification!,
                                       device connected: IOBluetoothDevice!) {
        guard let connected else { return }
        guard isOurHeadset(connected) else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            note("connect notification: \(connected.name ?? "?")")
            self.cachedAddress = connected.addressString ?? self.cachedAddress
            self.syncConnection(device: connected, live: true, forceRefreshOnConnect: true)
        }
    }

    @objc private func deviceDisconnected(_ notification: IOBluetoothUserNotification!,
                                          device disconnected: IOBluetoothDevice!) {
        guard let disconnected else { return }
        guard isOurHeadset(disconnected) else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            note("disconnect notification: \(disconnected.name ?? "?")")
            self.disconnectNotification?.unregister()
            self.disconnectNotification = nil
            // Trust the notification: isConnected() on the cached device object
            // never goes back to false in this process.
            self.syncConnection(device: disconnected, live: false, forceRefreshOnConnect: false)
        }
    }

    private func isOurHeadset(_ device: IOBluetoothDevice) -> Bool {
        if let wanted = preferredAddress?.replacingOccurrences(of: ":", with: "-").uppercased() {
            return (device.addressString ?? "").uppercased() == wanted
        }
        let name = (device.name ?? "").uppercased()
        return name.contains("AZ100") || name.contains("AZ-100")
            || name.contains("EAH-") || name.contains("TECHNICS")
    }

    /// Resolve the headset address once.  This is the only place that touches
    /// IOBluetoothDevice.pairedDevices(), and it is deliberately not part of the
    /// poll: pairedDevices() stalls outright while the controller powers down,
    /// and objects it returns keep reporting a stale isConnected() afterwards.
    private func resolveAddress() -> String? {
        if let preferred = preferredAddress {
            return preferred.replacingOccurrences(of: ":", with: "-").lowercased()
        }
        let paired = (IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice]) ?? []
        let candidates = paired.filter { isOurHeadset($0) }
        return (candidates.first { $0.isConnected() } ?? candidates.first)?.addressString
    }

    /// A freshly built device object is the only thing that reports the live link
    /// state; everything cached goes stale the moment the link drops.
    private func probeDevice() -> IOBluetoothDevice? {
        if cachedAddress == nil { cachedAddress = resolveAddress() }
        guard let cachedAddress else { return nil }
        return IOBluetoothDevice(addressString: cachedAddress)
    }

    /// A probe must never run on the main thread: IOBluetooth calls have been
    /// seen to stall while the controller powers down, and a stalled probe on
    /// the main thread freezes the whole menu bar.  A serial queue keeps the
    /// probes ordered, and the watchdog below clears the gate if one hangs.
    private func scheduleProbe(forceRefreshOnConnect: Bool = false) {
        guard !probeInFlight else { return }
        probeInFlight = true
        probeGeneration += 1
        let generation = probeGeneration

        probeQueue.async { [weak self] in
            guard let self else { return }
            let found = self.probeDevice()
            let live = found?.isConnected() ?? false
            DispatchQueue.main.async {
                guard self.probeGeneration == generation else { return }   // superseded
                self.probeInFlight = false
                self.syncConnection(device: found, live: live,
                                    forceRefreshOnConnect: forceRefreshOnConnect)
            }
        }

        DispatchQueue.main.asyncAfter(deadline: .now() + 4) { [weak self] in
            guard let self, self.probeInFlight, self.probeGeneration == generation else { return }
            // IOBluetooth stopped answering.  Bump the generation so the stalled
            // probe's result is discarded, and let the next tick try again.
            self.probeGeneration += 1
            self.probeInFlight = false
            note("probe stalled, retrying on the next tick")
        }
    }

    /// Reconcile against a child process.  A fresh `az100ctl devices` run is the
    /// only thing that still sees the true link state, because the in-process
    /// IOBluetoothDevice caches isConnected() at true forever.
    private func scheduleReconcile() {
        guard let helper = helperURL, !reconcileInFlight else { return }
        reconcileInFlight = true
        probeQueue.async { [weak self] in
            let process = Process()
            process.executableURL = helper
            process.arguments = ["devices"]
            let out = Pipe()
            process.standardOutput = out
            process.standardError = Pipe()
            var live = false
            var resolvedAddress: String?
            do {
                try process.run()
                let data = out.fileHandleForReading.readDataToEndOfFile()
                process.waitUntilExit()
                let text = String(data: data, encoding: .utf8) ?? ""
                var address: String?
                for line in text.split(separator: "\n") {
                    let fields = line.split(separator: "\t", omittingEmptySubsequences: false)
                    guard fields.count >= 3 else { continue }
                    let name = String(fields[0])
                    let addr = String(fields[1])
                    if self?.isOurHeadset(name: name, address: addr) != true { continue }
                    address = addr
                    live = fields[2].trimmingCharacters(in: .whitespaces) == "connected"
                }
                if let address { resolvedAddress = address }
            } catch {
                // leave live as-is
            }
            let device = resolvedAddress.flatMap { IOBluetoothDevice(addressString: $0) }
            DispatchQueue.main.async {
                self?.reconcileInFlight = false
                self?.cachedAddress = resolvedAddress ?? self?.cachedAddress
                self?.syncConnection(device: device, live: live, forceRefreshOnConnect: false)
            }
        }
    }

    private func isOurHeadset(name: String, address: String) -> Bool {
        if let cachedAddress, !cachedAddress.isEmpty {
            return address.lowercased() == cachedAddress.lowercased()
        }
        let upper = name.uppercased()
        return upper.contains("AZ100") || upper.contains("AZ-100")
            || upper.contains("EAH-") || upper.contains("TECHNICS")
    }

    private func syncConnection(device found: IOBluetoothDevice?, live nowConnected: Bool,
                                forceRefreshOnConnect: Bool) {
        device = found

        if nowConnected != connected {
            connected = nowConnected
            if nowConnected {
                note("headset connected: \(found?.name ?? "?")")
                showStatusItem()
                // Re-register every time: the notification belongs to this device
                // object and is torn down once it has fired.
                disconnectNotification?.unregister()
                disconnectNotification = found?.register(
                    forDisconnectNotification: self, selector: #selector(deviceDisconnected(_:device:)))
                note("disconnect notification registered: \(disconnectNotification != nil)")
                refresh(reason: "connect")
                return
            }
            note("headset disconnected, hiding the status item")
            hideStatusItem()
            state = nil
            lastError = nil
            return
        }

        if nowConnected && state == nil && !busy {
            // Back off when the last attempt failed: the SPP channel may be held
            // by a phone, and hammering it every poll only wastes the headset's time.
            let wait: TimeInterval = lastError == nil ? 15 : 60
            if Date().timeIntervalSince(lastAttempt) >= wait {
                refresh(reason: forceRefreshOnConnect ? "launch" : "idle")
            }
        }
        if !nowConnected && alwaysShow && statusItem == nil {
            showStatusItem()
        }
    }

    private func showStatusItem() {
        guard statusItem == nil else { return }
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        item.menu = menu
        statusItem = item
        rebuildMenu()
        updateTitle()
    }

    private func hideStatusItem() {
        guard let item = statusItem else { return }
        NSStatusBar.system.removeStatusItem(item)
        statusItem = nil
    }

    // MARK: helper

    private func runHelper(_ arguments: [String]) -> (status: Int32, output: String) {
        guard let helper = helperURL, FileManager.default.isExecutableFile(atPath: helper.path) else {
            return (-1, "")
        }
        let process = Process()
        process.executableURL = helper
        process.arguments = arguments
        let out = Pipe()
        process.standardOutput = out
        process.standardError = Pipe()
        do {
            try process.run()
        } catch {
            return (-1, "")
        }
        let data = out.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        return (process.terminationStatus, String(data: data, encoding: .utf8) ?? "")
    }

    private func refresh(reason: String) {
        guard !busy else { return }
        busy = true
        lastAttempt = Date()
        lastError = nil
        updateTitle()
        rebuildMenu()

        DispatchQueue.global(qos: .utility).async { [weak self] in
            guard let self else { return }
            let result = self.runHelper(["status", "-t", helperTimeout])
            let parsed = Az100State(output: result.output)

            DispatchQueue.main.async {
                self.busy = false
                if let parsed {
                    self.state = parsed
                    note("state: mode=\(parsed.mode) left=\(parsed.left) right=\(parsed.right) case=\(parsed.cradle)")
                } else if result.status != 0 {
                    self.lastError = self.describe(result.status)
                    note("refresh(\(reason)) failed: \(self.lastError ?? "")")
                } else {
                    note("refresh(\(reason)) returned nothing usable")
                }
                self.rebuildMenu()
                self.updateTitle()
            }
        }
    }

    private func switchMode(_ target: String) {
        guard !busy else { return }
        busy = true
        lastAttempt = Date()
        updateTitle()
        rebuildMenu()

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self else { return }
            let result = self.runHelper([target, "-t", helperTimeout])
            let parsed = Az100State(output: result.output)

            DispatchQueue.main.async {
                self.busy = false
                if let parsed {
                    self.state = parsed
                    self.lastError = nil
                    note("mode -> \(parsed.mode)")
                } else if result.status != 0 {
                    self.lastError = self.describe(result.status)
                    note("switch to \(target) failed: \(self.lastError ?? "")")
                }
                self.rebuildMenu()
                self.updateTitle()
            }
        }
    }

    /// Turning LE Audio on or off makes the headset drop the link and come back
    /// on the other transport, so the answer may never arrive.  Treat that as
    /// "sent" and let the next refresh show the truth.
    private func switchLeAudio(_ on: Bool) {
        guard !busy else { return }
        busy = true
        lastAttempt = Date()
        updateTitle()
        rebuildMenu()

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self else { return }
            let result = self.runHelper(["leaudio", on ? "on" : "off", "-t", helperTimeout])
            let answer = LeAudioResult(output: result.output)

            DispatchQueue.main.async {
                self.busy = false
                if answer.value >= 0 {
                    self.state?.leAudio = answer.value
                    self.lastError = nil
                    note("le audio -> \(answer.value)")
                } else if result.status != 0 {
                    self.lastError = self.describe(result.status)
                    note("le audio \(on ? "on" : "off") failed: \(self.lastError ?? "")")
                } else {
                    note("le audio \(on ? "on" : "off") sent, link dropped")
                }
                self.rebuildMenu()
                self.updateTitle()
            }
        }
    }

    private func describe(_ status: Int32) -> String {
        switch status {
        case 2: return "找不到已配对的耳机"
        case 3: return "打不开 SPP 通道（可能被手机占用）"
        case 4: return "耳机没有回应"
        case 9: return "超时"
        default: return "失败（退出码 \(status)）"
        }
    }

    // MARK: ui

    private func updateTitle() {
        guard let button = statusItem?.button else { return }

        guard let state else {
            button.title = busy ? "…" : "AZ100"
            button.toolTip = lastError ?? "正在读取耳机状态…"
            return
        }

        let headline = state.headlineBattery
        var title = modeLabel(state.mode)
        if headline.value >= 0 {
            title += " \(headline.isCase ? "盒" : "")\(batteryLabel(headline.value))"
        } else {
            title += " --"
        }
        if busy { title += " …" }
        button.title = title

        var tip = "\(state.name)  \(state.address)\n模式：\(modeLabel(state.mode))"
        if state.mode == "anc" || state.mode == "adaptive" {
            tip += "（降噪电平 \(state.ancLevel)）"
        } else if state.mode == "ambient" {
            tip += "（通透电平 \(state.ambientLevel)）"
        }
        tip += "\n左耳 \(batteryLabel(state.left))   右耳 \(batteryLabel(state.right))   充电盒 \(batteryLabel(state.cradle))"
        tip += "\nLE Audio（LC3）：\(leAudioLabel(state.leAudio))"
        if let lastError { tip += "\n⚠︎ \(lastError)" }
        button.toolTip = tip
    }

    private func rebuildMenu() {
        menu.removeAllItems()

        if let state {
            menu.addItem(info("\(state.name)  \(state.address)"))
            menu.addItem(info("左耳 \(batteryLabel(state.left))    右耳 \(batteryLabel(state.right))    充电盒 \(batteryLabel(state.cradle))"))
            menu.addItem(info("LE Audio（LC3）  \(leAudioLabel(state.leAudio))"))
        } else if let lastError {
            menu.addItem(info("⚠︎ \(lastError)"))
        } else {
            menu.addItem(info(busy ? "正在读取…" : "等待耳机…"))
        }

        menu.addItem(.separator())

        let header = info("降噪模式")
        menu.addItem(header)

        let modes: [(String, String)] = [
            ("anc", "降噪"), ("ambient", "通透"), ("off", "关闭"), ("adaptive", "自适应"),
        ]
        for (value, label) in modes {
            let item = NSMenuItem(title: label, action: #selector(selectMode(_:)), keyEquivalent: "")
            item.target = self
            item.representedObject = value
            item.indentationLevel = 1
            item.state = (state?.mode == value) ? .on : .off
            item.isEnabled = !busy && connected
            menu.addItem(item)
        }

        menu.addItem(.separator())

        menu.addItem(info("LE Audio（LC3）"))
        for (value, label) in [(1, "开"), (0, "关")] {
            let item = NSMenuItem(title: label, action: #selector(selectLeAudio(_:)), keyEquivalent: "")
            item.target = self
            item.representedObject = value
            item.indentationLevel = 1
            item.state = (state?.leAudio == value) ? .on : .off
            item.isEnabled = !busy && connected
            menu.addItem(item)
        }

        menu.addItem(.separator())

        let refreshItem = NSMenuItem(title: busy ? "刷新中…" : "刷新", action: #selector(refreshNow), keyEquivalent: "r")
        refreshItem.target = self
        refreshItem.isEnabled = !busy && connected
        menu.addItem(refreshItem)

        menu.addItem(.separator())

        let quit = NSMenuItem(title: "退出", action: #selector(quit), keyEquivalent: "q")
        quit.target = self
        menu.addItem(quit)
    }

    private func info(_ title: String) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        item.isEnabled = false
        return item
    }

    @objc private func selectMode(_ sender: NSMenuItem) {
        guard let target = sender.representedObject as? String else { return }
        switchMode(target)
    }

    @objc private func selectLeAudio(_ sender: NSMenuItem) {
        guard let value = sender.representedObject as? Int else { return }
        switchLeAudio(value == 1)
    }

    @objc private func refreshNow() {
        refresh(reason: "manual")
    }

    @objc private func quit() {
        NSApp.terminate(nil)
    }
}

// MARK: - main

let application = NSApplication.shared
let delegate = AppDelegate()
application.delegate = delegate
application.run()

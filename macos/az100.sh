#!/bin/bash
#
# az100.sh — 在 macOS 上查看 / 切换 Technics EAH-AZ100 的降噪模式与电量。
#
# 协议来自本仓库的 Airoha SPP/RACE 实现（AirohaRace.java / DirectAirohaController.java）：
#   RFCOMM/SPP 服务 "Airoha_APP" (UUID 00000000-0000-0000-0099-AABBCCDDEEFF)
#   命令帧   05 5A <len LE16> <race id LE16> <payload>
#   应答帧   05 5B <len LE16> <race id LE16> <status> ...
#   主动上报 05 5D ...   （降噪状态、耳机电量）
# 没有握手，没有校验和：连上就写，按长度字段读。
#
# macOS 不向 shell 暴露 RFCOMM socket，所以脚本内嵌了一个用 IOBluetooth 写的
# Swift 小工具，首次运行时用系统自带的 swiftc 编译进缓存目录，之后直接复用。
# 只依赖 Xcode Command Line Tools（xcode-select --install），不需要安装任何东西。
#
#   ./az100.sh                      查看模式 + 电量
#   ./az100.sh status               同上
#   ./az100.sh mode                 只查看当前模式
#   ./az100.sh anc                  切到降噪
#   ./az100.sh ambient              切到通透
#   ./az100.sh off                  关闭降噪
#   ./az100.sh adaptive             自适应降噪
#   ./az100.sh toggle               关闭 → 降噪 → 通透 → 关闭 循环
#   ./az100.sh battery              只查看电量
#   ./az100.sh leaudio              查看 LE Audio（LC3）开关
#   ./az100.sh leaudio on|off       开 / 关 LE Audio（耳机会断开重连一次）
#   ./az100.sh watch [秒]           持续刷新，默认每 5 秒
#   ./az100.sh devices              列出已配对的蓝牙设备
#   ./az100.sh raw 05 5A 02 00 0A 00   直接发一帧并打印回包（排障用）
#
# 选项：
#   -d, --device <AA:BB:CC:DD:EE:FF>   指定耳机地址（默认自动找已配对的 AZ100）
#   -t, --timeout <秒>                 单条命令超时，默认 6（与官方 Audio Connect 一致）
#   -c, --channel <n>                  强制 RFCOMM 通道（默认走 SDP 解析，通常是 21）
#   -j, --json                         以 KEY=VALUE 输出，便于脚本消费
#   -v, --debug                        打印每一帧原始字节
#   -h, --help                         显示帮助
#
# 配置：把 AZ100_MAC=AA:BB:CC:DD:EE:FF 写进 ~/.config/az100/config 即可固定设备。

set -euo pipefail

VERSION="1.0.0"
SELF="${BASH_SOURCE[0]}"
[ -f "$SELF" ] || SELF="$0"

CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/az100"
CONFIG_FILE="${XDG_CONFIG_HOME:-$HOME/.config}/az100/config"

# ---------------------------------------------------------------- 命令行解析

DEVICE="${AZ100_MAC:-}"
TIMEOUT=""
CHANNEL=""
AS_JSON=0
DEBUG=0
COMMAND=""
COMMAND_ARG=""
RAW_BYTES=()

usage() {
    sed -n '2,/^set -euo pipefail$/p' "$SELF" | sed '$d' | sed 's/^#\{1,2\} \{0,1\}//'
    exit "${1:-0}"
}

while [ $# -gt 0 ]; do
    case "$1" in
        -d|--device)  [ $# -ge 2 ] || { echo "缺少 --device 的参数" >&2; exit 1; }; DEVICE="$2"; shift 2 ;;
        -t|--timeout) [ $# -ge 2 ] || { echo "缺少 --timeout 的参数" >&2; exit 1; }; TIMEOUT="$2"; shift 2 ;;
        -c|--channel) [ $# -ge 2 ] || { echo "缺少 --channel 的参数" >&2; exit 1; }; CHANNEL="$2"; shift 2 ;;
        -j|--json)    AS_JSON=1; shift ;;
        -v|--debug)   DEBUG=1; shift ;;
        -h|--help)    usage 0 ;;
        --)           shift; break ;;
        -*)           echo "未知选项：$1" >&2; usage 1 ;;
        *)
            if [ -z "$COMMAND" ]; then
                COMMAND="$1"
            elif [ "$COMMAND" = "raw" ]; then
                RAW_BYTES+=("$1")
            elif [ -z "$COMMAND_ARG" ]; then
                COMMAND_ARG="$1"
            else
                echo "多余的参数：$1" >&2; usage 1
            fi
            shift
            ;;
    esac
done

while [ $# -gt 0 ]; do
    if [ "$COMMAND" = "raw" ]; then RAW_BYTES+=("$1"); else COMMAND_ARG="$1"; fi
    shift
done

# 配置文件里的 AZ100_MAC 优先级低于命令行，高于自动探测。
if [ -z "$DEVICE" ] && [ -f "$CONFIG_FILE" ]; then
    # shellcheck disable=SC1090
    . "$CONFIG_FILE" 2>/dev/null || true
    DEVICE="${AZ100_MAC:-}"
fi

[ -n "$COMMAND" ] || COMMAND="status"

# ---------------------------------------------------------------- 构建内嵌工具

extract_swift() {
    awk '/^: <<.AZ100_SWIFT_SOURCE.$/ { copy = 1; next } /^AZ100_SWIFT_SOURCE$/ { copy = 0 } copy' "$SELF"
}

ensure_helper() {
    local source="$CACHE_DIR/az100ctl.swift"
    local binary="$CACHE_DIR/az100ctl"
    local stamp="$CACHE_DIR/source.sha256"
    local wanted current

    mkdir -p "$CACHE_DIR"
    wanted="$(extract_swift | shasum -a 256 | awk '{print $1}')"
    current="$(cat "$stamp" 2>/dev/null || true)"

    if [ -x "$binary" ] && [ "$wanted" = "$current" ]; then
        printf '%s' "$binary"
        return 0
    fi

    if ! command -v swiftc >/dev/null 2>&1; then
        cat >&2 <<'EOF'
需要 Xcode Command Line Tools 里的 swiftc 来编译内嵌的蓝牙小工具。
请先执行：xcode-select --install
EOF
        return 1
    fi

    extract_swift > "$source"
    if ! swiftc -O -o "$binary.tmp" "$source" 2>"$CACHE_DIR/build.log"; then
        echo "编译失败，日志在 $CACHE_DIR/build.log" >&2
        tail -20 "$CACHE_DIR/build.log" >&2
        return 1
    fi
    mv "$binary.tmp" "$binary"
    printf '%s' "$wanted" > "$stamp"
    printf '%s' "$binary"
}

# ---------------------------------------------------------------- 输出辅助

if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
    C_RESET=$'\033[0m'; C_DIM=$'\033[2m'; C_BOLD=$'\033[1m'
    C_GREEN=$'\033[32m'; C_BLUE=$'\033[36m'; C_YELLOW=$'\033[33m'
else
    C_RESET=""; C_DIM=""; C_BOLD=""; C_GREEN=""; C_BLUE=""; C_YELLOW=""
fi

mode_name() {
    case "$1" in
        anc)      echo "降噪" ;;
        ambient)  echo "通透" ;;
        off)      echo "关闭" ;;
        adaptive) echo "自适应" ;;
        *)        echo "未知" ;;
    esac
}

mode_color() {
    case "$1" in
        anc)      printf '%s' "$C_GREEN" ;;
        ambient)  printf '%s' "$C_BLUE" ;;
        adaptive) printf '%s' "$C_YELLOW" ;;
        *)        printf '%s' "$C_DIM" ;;
    esac
}

# 把 0..100 画成进度条；负数表示耳机没有上报。
battery_bar() {
    local value="$1" width=10 filled=0 i out=""
    if [ "$value" -ge 0 ] && [ "$value" -le 100 ]; then
        filled=$((value * width / 100))
    fi
    for ((i = 0; i < width; i++)); do
        if [ "$value" -ge 0 ] && [ "$i" -lt "$filled" ]; then out="${out}█"; else out="${out}░"; fi
    done
    printf '%s' "$out"
}

battery_text() {
    local value="$1" raw="$2"
    if [ "$value" -ge 0 ]; then
        printf '%s%3d%%%s' "$C_BOLD" "$value" "$C_RESET"
    elif [ "$raw" -ge 0 ]; then
        printf '%s  --  (原始 0x%02X)%s' "$C_DIM" "$raw" "$C_RESET"
    else
        printf '%s  --  %s' "$C_DIM" "$C_RESET"
    fi
}

human_error() {
    case "$1" in
        2) echo "找不到耳机：请先在「系统设置 → 蓝牙」里配对并连接 Technics EAH-AZ100。" ;;
        3) echo "打不开 SPP 通道：耳机可能正被手机占用，或已断开。请确认它连着这台 Mac。" ;;
        4) echo "耳机没有回应。靠近一点，或稍后重试。" ;;
        9) echo "超时。" ;;
        *) echo "失败（退出码 $1）。加 -v 可以看到每一帧原始字节。" ;;
    esac
}

# ---------------------------------------------------------------- 执行

run_helper() {
    local binary
    binary="$(ensure_helper)" || exit 1

    local args=()
    [ -n "$DEVICE" ]  && args+=(--addr "$DEVICE")
    [ -n "$TIMEOUT" ] && args+=(--timeout "$TIMEOUT")
    [ -n "$CHANNEL" ] && args+=(--channel "$CHANNEL")
    [ "$DEBUG" -eq 1 ] && args+=(--debug)
    args+=("$@")

    "$binary" "${args[@]}"
}

# 把 helper 的 KEY=VALUE 输出读进当前 shell；失败时打印人话并返回非零。
load_state() {
    local output rc=0 key value
    output="$(run_helper "$@")" || rc=$?
    if [ "$rc" -ne 0 ]; then
        human_error "$rc" >&2
        return "$rc"
    fi
    while IFS='=' read -r key value; do
        case "$key" in
            DEVICE_NAME|ADDRESS|MODE|MODE_BYTE|ANC_LEVEL|AMBIENT_LEVEL|ADAPTIVE|\
            LEFT|RIGHT|CASE|LEFT_RAW|RIGHT_RAW|CASE_RAW|AGENT|\
            LE_AUDIO|LE_AUDIO_REQUESTED|LE_AUDIO_LINK_DROPPED)
                printf -v "$key" '%s' "$value" ;;
        esac
    done <<< "$output"
    return 0
}

reset_state() {
    DEVICE_NAME=""; ADDRESS=""; MODE=""; MODE_BYTE=""; ANC_LEVEL=""; AMBIENT_LEVEL=""; ADAPTIVE=""
    LEFT=-1; RIGHT=-1; CASE=-1; LEFT_RAW=-1; RIGHT_RAW=-1; CASE_RAW=-1; AGENT=""
    LE_AUDIO=-1; LE_AUDIO_REQUESTED=""; LE_AUDIO_LINK_DROPPED=0
}

leaudio_text() {
    case "$1" in
        1) printf '%s开（LE Audio / LC3）%s' "$C_GREEN" "$C_RESET" ;;
        0) printf '%s关（经典蓝牙）%s' "$C_DIM" "$C_RESET" ;;
        *) printf '%s未知%s' "$C_DIM" "$C_RESET" ;;
    esac
}

print_leaudio() {
    if [ "$AS_JSON" -eq 1 ]; then
        echo "LE_AUDIO=$LE_AUDIO"
        [ -n "$LE_AUDIO_REQUESTED" ] && echo "LE_AUDIO_REQUESTED=$LE_AUDIO_REQUESTED"
        echo "LE_AUDIO_LINK_DROPPED=$LE_AUDIO_LINK_DROPPED"
        return 0
    fi
    printf '  LE Audio    %s' "$(leaudio_text "$LE_AUDIO")"
    if [ "$LE_AUDIO_LINK_DROPPED" = "1" ]; then
        printf '  %s(耳机已断开重连)%s' "$C_DIM" "$C_RESET"
    fi
    printf '\n'
}

print_state() {
    local show_battery="$1"

    if [ "$AS_JSON" -eq 1 ]; then
        echo "DEVICE=${DEVICE_NAME}"
        echo "ADDRESS=${ADDRESS}"
        echo "MODE=$MODE"
        echo "MODE_BYTE=$MODE_BYTE"
        echo "ANC_LEVEL=$ANC_LEVEL"
        echo "AMBIENT_LEVEL=$AMBIENT_LEVEL"
        echo "ADAPTIVE=$ADAPTIVE"
        echo "LE_AUDIO=$LE_AUDIO"
        if [ "$show_battery" -eq 1 ]; then
            echo "LEFT=$LEFT"
            echo "RIGHT=$RIGHT"
            echo "CASE=$CASE"
            echo "LEFT_RAW=$LEFT_RAW"
            echo "RIGHT_RAW=$RIGHT_RAW"
            echo "CASE_RAW=$CASE_RAW"
            echo "AGENT=$AGENT"
        fi
        return 0
    fi

    if [ -n "$DEVICE_NAME" ]; then
        printf '%s%s%s' "$C_DIM" "$DEVICE_NAME" "$C_RESET"
        [ -n "$ADDRESS" ] && printf '  %s%s%s' "$C_DIM" "$ADDRESS" "$C_RESET"
        printf '\n'
    fi

    printf '  当前模式    %s%s%s' "$(mode_color "$MODE")" "$(mode_name "$MODE")" "$C_RESET"
    case "$MODE" in
        anc|adaptive) printf '  %s降噪电平 %s%s' "$C_DIM" "$ANC_LEVEL" "$C_RESET" ;;
        ambient)      printf '  %s通透电平 %s%s' "$C_DIM" "$AMBIENT_LEVEL" "$C_RESET" ;;
    esac
    printf '\n'

    if [ "$LE_AUDIO" != "-1" ]; then
        printf '  LE Audio    %s\n' "$(leaudio_text "$LE_AUDIO")"
    fi

    if [ "$show_battery" -eq 1 ]; then
        printf '  左耳        %s  %s\n' "$(battery_bar "$LEFT")" "$(battery_text "$LEFT" "$LEFT_RAW")"
        printf '  右耳        %s  %s\n' "$(battery_bar "$RIGHT")" "$(battery_text "$RIGHT" "$RIGHT_RAW")"
        printf '  充电盒      %s  %s\n' "$(battery_bar "$CASE")" "$(battery_text "$CASE" "$CASE_RAW")"
    fi
}

# ---------------------------------------------------------------- 分发

case "$COMMAND" in
    help|--help|-h) usage 0 ;;

    devices)
        run_helper devices
        ;;

    status|st|battery|bat)
        reset_state
        if [ "$COMMAND" = "battery" ] || [ "$COMMAND" = "bat" ]; then
            load_state battery
        else
            load_state status
        fi
        print_state 1
        ;;

    mode)
        reset_state
        if [ -z "$COMMAND_ARG" ]; then
            load_state mode
            print_state 0
        else
            case "$COMMAND_ARG" in
                降噪)   COMMAND_ARG="anc" ;;
                通透)   COMMAND_ARG="ambient" ;;
                关闭)   COMMAND_ARG="off" ;;
                自适应) COMMAND_ARG="adaptive" ;;
            esac
            load_state "$COMMAND_ARG"
            print_state 0
        fi
        ;;

    leaudio|lea|lc3)
        reset_state
        if [ -z "$COMMAND_ARG" ]; then
            load_state leaudio
            print_leaudio
        else
            case "$COMMAND_ARG" in
                开启|开)  COMMAND_ARG="on" ;;
                关闭|关)  COMMAND_ARG="off" ;;
            esac
            case "$COMMAND_ARG" in
                on|off|1|0) ;;
                *) echo "用法：az100.sh leaudio on|off" >&2; exit 1 ;;
            esac
            load_state leaudio "$COMMAND_ARG"
            print_leaudio
            if [ "$AS_JSON" -eq 0 ] && [ "$LE_AUDIO_LINK_DROPPED" = "1" ]; then
                printf '  %s开关已下发；等耳机重连后用 az100.sh leaudio 复核。%s\n' "$C_DIM" "$C_RESET"
            fi
        fi
        ;;

    # 直接写模式名：./az100.sh anc / ambient / off / adaptive / toggle
    anc|noise|ambient|transparency|off|adaptive|toggle)
        reset_state
        load_state "$COMMAND"
        print_state 0
        ;;

    watch)
        interval="${COMMAND_ARG:-5}"
        while true; do
            [ -t 1 ] && clear
            printf '%s%s%s\n' "$C_DIM" "$(date '+%H:%M:%S')" "$C_RESET"
            reset_state
            if load_state status; then print_state 1; else echo "读取失败，稍后重试…" >&2; fi
            sleep "$interval"
        done
        ;;

    raw)
        if [ "${#RAW_BYTES[@]}" -eq 0 ]; then
            echo "用法：az100.sh raw 05 5A 02 00 0A 00" >&2
            exit 1
        fi
        run_helper raw "${RAW_BYTES[@]}"
        ;;

    *)
        echo "未知命令：$COMMAND" >&2
        usage 1
        ;;
esac

exit 0

# ---------------------------------------------------------------------------
# 下面是被本脚本原样提取的 Swift 源码，shell 永远不会执行到这里。
# ---------------------------------------------------------------------------
: <<'AZ100_SWIFT_SOURCE'
// az100ctl — Airoha SPP/RACE client for the Technics EAH-AZ100, over macOS IOBluetooth.
//
// The wire protocol was recovered from Audio Connect 4.4.0 and is reimplemented
// in this repository (AirohaRace.java, DirectAirohaController.java):
//
//   transport : RFCOMM / SPP, service "Airoha_APP",
//               UUID 00000000-0000-0000-0099-AABBCCDDEEFF (resolved through SDP)
//   frames    : H4 / RACE   05 <type> <len LE16> <race id LE16> <payload>
//   answer    :             05 5B <len LE16> <race id LE16> <status> ...
//   indication:             05 5D ...      (unsolicited: outside control, TWS battery)
//
// No handshake, no checksum: connect, write, read.

import Foundation
import IOBluetooth

// MARK: - protocol constants

private let SPP_UUID_BYTES: [UInt8] = [
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF,
]

private let RID_GET_OUTSIDE  = 10    // RACE_CUSTOMER_GET_OUTSIDE_CTRL
private let RID_SET_OUTSIDE  = 11    // RACE_CUSTOMER_SET_OUTSIDE_CTRL
private let RID_GET_CRADLE   = 64    // RACE_CUSTOMER_GET_CRADLE_BATTERY
private let RID_GET_ADAPTIVE = 103   // RACE_CUSTOMER_GET_ADAPTIVE_ANC
private let RID_SET_ADAPTIVE = 104   // RACE_CUSTOMER_SET_ADAPTIVE_ANC
private let RID_GET_AGENT    = 3284  // RACE_BLUETOOTH_IS_AGENT_RIGHT_DEVICE
private let RID_GET_BATTERY  = 3286  // RACE_BLUETOOTH_TWS_GET_BATTERY
private let RID_GET_LE_AUDIO = 89    // RACE_CUSTOMER_GET_LE_AUDIO
private let RID_SET_LE_AUDIO = 90    // RACE_CUSTOMER_SET_LE_AUDIO

private let TYPE_RESP = 0x5B
private let TYPE_IND  = 0x5D
private let TYPE_CMD  = 0x5A

private let CTRL_OFF = 0
private let CTRL_ANC = 1
private let CTRL_AMBIENT = 2

/// Outside-control levels are validated by the headset: 0..100, and a packet
/// carrying the class defaults (0/10) is refused.  A fresh AZ100 answers 100/100.
private let DEFAULT_LEVEL = 100

/// The first RFCOMM open in a fresh process never completes on macOS; the second
/// one always does.  Keep the first attempt short so the tool stays responsive.
private let OPEN_ATTEMPT_TIMEOUTS: [TimeInterval] = [0.5, 8, 8]

// MARK: - helpers

/// Write a whole line to a raw fd.  FileHandle.writeData raises an Objective-C
/// exception on a broken pipe, and warn() is called from the RFCOMM callback
/// thread -- an exception there aborts the process.  write(2) cannot raise.
private func writeLine(_ descriptor: Int32, _ text: String) {
    let bytes = Array((text + "\n").utf8)
    bytes.withUnsafeBufferPointer { buffer in
        guard let base = buffer.baseAddress else { return }
        var offset = 0
        while offset < buffer.count {
            let written = Darwin.write(descriptor, base + offset, buffer.count - offset)
            if written <= 0 { break }        // EPIPE, EINTR, ... -- drop the line
            offset += written
        }
    }
}

private func warn(_ message: String) {
    writeLine(STDERR_FILENO, message)
}

private func hexString(_ bytes: [UInt8]) -> String {
    bytes.map { String(format: "%02X", $0) }.joined(separator: " ")
}

/// Spin the main run loop so IOBluetooth can deliver its delegate callbacks.
private func pump(_ seconds: TimeInterval) {
    let end = Date().addingTimeInterval(seconds)
    while Date() < end {
        RunLoop.main.run(until: min(end, Date().addingTimeInterval(0.05)))
    }
}

// MARK: - frames

private struct Frame {
    let raw: [UInt8]

    var isRace: Bool {
        let channel = Int(raw[0])
        return channel == 5 || channel == 0x15 || channel == 7
    }
    var type: Int { raw.count > 1 ? Int(raw[1]) : 0 }
    var raceId: Int { raw.count >= 6 ? Int(raw[4]) | (Int(raw[5]) << 8) : -1 }
    var status: Int { raw.count > 6 ? Int(raw[6]) : -1 }
    func u8(_ index: Int) -> Int { index < raw.count ? Int(raw[index]) : -1 }
}

/// 05 5A <len LE16> <race id LE16> <payload>, with len = 2 + payload.count.
private func commandFrame(_ raceId: Int, _ payload: [UInt8]?) -> [UInt8] {
    let body = payload ?? []
    let length = 2 + body.count
    return [
        0x05, UInt8(TYPE_CMD),
        UInt8(length & 0xFF), UInt8((length >> 8) & 0xFF),
        UInt8(raceId & 0xFF), UInt8((raceId >> 8) & 0xFF),
    ] + body
}

/// Incremental H4 parser, mirroring H4Transport.parseRxDataToPacket: an unknown
/// channel byte, or an impossible ACL/HCI length, drops one byte and retries.
private final class H4Parser {
    private var buffer: [UInt8] = []
    private var sink: (([UInt8]) -> Void)?

    func setSink(_ sink: @escaping ([UInt8]) -> Void) { self.sink = sink }

    func feed(_ data: [UInt8]) {
        buffer.append(contentsOf: data)
        var position = 0
        while true {
            let length = frameLength(position)
            if length == 0 { break }
            if length < 0 { position += 1; continue }
            let frame = Array(buffer[position..<(position + length)])
            position += length
            sink?(frame)
        }
        if position > 0 { buffer.removeFirst(position) }
    }

    private func frameLength(_ position: Int) -> Int {
        let available = buffer.count - position
        if available < 1 { return 0 }
        let channel = Int(buffer[position])

        if channel == 4 {                                    // HCI event
            if available >= 2 {
                let event = Int(buffer[position + 1])
                if event != 0xFF && event != 0x0E && event != 0x0F { return -1 }
            }
            if available < 3 { return 0 }
            let length = 3 + Int(buffer[position + 2])
            return available >= length ? length : 0
        }
        if channel == 2 {                                    // ACL
            if available >= 3, Int(buffer[position + 1]) != 0 || Int(buffer[position + 2]) != 0x0F {
                return -1
            }
            if available < 5 { return 0 }
            let body = Int(buffer[position + 3]) | (Int(buffer[position + 4]) << 8)
            if body == 0 || body > 1995 { return -1 }
            let length = 5 + body
            return available >= length ? length : 0
        }
        if channel == 5 || channel == 0x15 || channel == 7 {  // RACE
            if available < 4 { return 0 }
            let length = 4 + (Int(buffer[position + 2]) | (Int(buffer[position + 3]) << 8))
            if length > 2000 || length < 6 { return -1 }
            return available >= length ? length : 0
        }
        return -1
    }
}

// MARK: - link

private final class Link: NSObject, IOBluetoothRFCOMMChannelDelegate {
    private var channel: IOBluetoothRFCOMMChannel?
    private let parser = H4Parser()
    /// Every frame that ever came off the socket, in arrival order.  Nothing is
    /// consumed, so a frame that is not the answer to the current command (an
    /// indication bundled into the same RFCOMM read, for instance) stays available.
    private(set) var frames: [Frame] = []
    private var openFinished = false
    private var openStatus: IOReturn = -1

    var debug = false

    // IOBluetoothRFCOMMChannelDelegate
    @objc func rfcommChannelOpenComplete(_ c: IOBluetoothRFCOMMChannel!, status: IOReturn) {
        openFinished = true
        openStatus = status
    }

    @objc func rfcommChannelData(_ c: IOBluetoothRFCOMMChannel!, data pointer: UnsafeMutableRawPointer!,
                                 length count: Int) {
        let bytes = Array(UnsafeRawBufferPointer(start: pointer, count: count))
        if debug { warn("rx raw   \(hexString(bytes))") }
        parser.feed(bytes)
    }

    @objc func rfcommChannelClosed(_ c: IOBluetoothRFCOMMChannel!) {
        if debug { warn("channel closed") }
    }

    /// Resolve the RFCOMM channel for the Airoha SPP UUID from the device's SDP records.
    static func resolveChannel(_ device: IOBluetoothDevice) -> BluetoothRFCOMMChannelID? {
        let uuid = SPP_UUID_BYTES.withUnsafeBytes {
            IOBluetoothSDPUUID(bytes: $0.baseAddress, length: 16)
        }
        guard let record = device.getServiceRecord(for: uuid) else { return nil }
        var channelID: BluetoothRFCOMMChannelID = 0
        guard record.getRFCOMMChannelID(&channelID) == kIOReturnSuccess else { return nil }
        return channelID
    }

    func open(_ device: IOBluetoothDevice, channelID: BluetoothRFCOMMChannelID) -> Bool {
        parser.setSink { [weak self] raw in
            let frame = Frame(raw: raw)
            guard let self else { return }
            if self.debug, frame.isRace, frame.type == TYPE_RESP || frame.type == TYPE_IND {
                warn("rx       \(hexString(raw))")
            }
            self.frames.append(frame)
        }

        for (attempt, timeout) in OPEN_ATTEMPT_TIMEOUTS.enumerated() {
            openFinished = false
            openStatus = -1

            var candidate: IOBluetoothRFCOMMChannel? = nil
            let result = device.openRFCOMMChannelAsync(&candidate, withChannelID: channelID, delegate: self)
            guard result == kIOReturnSuccess, let c = candidate else {
                if debug { warn("open attempt \(attempt + 1): openRFCOMMChannelAsync -> \(result)") }
                pump(0.3)
                continue
            }
            channel = c
            _ = c.setDelegate(self)

            let deadline = Date().addingTimeInterval(timeout)
            while !openFinished && Date() < deadline { pump(0.05) }

            if openFinished && openStatus == kIOReturnSuccess && c.isOpen() {
                if debug { warn("open attempt \(attempt + 1): ok") }
                return true
            }
            if debug {
                warn("open attempt \(attempt + 1): finished=\(openFinished) "
                     + "status=\(openStatus) isOpen=\(c.isOpen())")
            }
            _ = c.close()
            channel = nil
            pump(0.4)
        }
        return false
    }

    func close() {
        if let c = channel { _ = c.close() }
        channel = nil
    }

    @discardableResult
    func write(_ bytes: [UInt8]) -> Bool {
        guard let c = channel, c.isOpen() else { return false }
        var buffer = bytes
        let result = buffer.withUnsafeMutableBytes {
            c.writeSync($0.baseAddress, length: UInt16(bytes.count))
        }
        if debug { warn("tx       \(hexString(bytes)) -> \(result)") }
        return result == kIOReturnSuccess
    }

    func send(_ raceId: Int, _ payload: [UInt8]? = nil) {
        _ = write(commandFrame(raceId, payload))
    }

    /// Wait until a frame at or after `index` satisfies `match`, or the timeout
    /// elapses.  Because `frames` is append-only this never drops anything.
    func waitFor(_ timeout: TimeInterval, from index: Int, _ match: (Frame) -> Bool) -> Frame? {
        let deadline = Date().addingTimeInterval(timeout)
        var position = index
        while true {
            while position < frames.count {
                let frame = frames[position]
                position += 1
                if match(frame) { return frame }
            }
            if Date() >= deadline { return nil }
            pump(0.05)
        }
    }

    /// Send a command and wait for its 0x5B answer.
    func command(_ raceId: Int, _ payload: [UInt8]? = nil, timeout: TimeInterval = 6) -> Frame? {
        let start = frames.count
        send(raceId, payload)
        return waitFor(timeout, from: start) {
            $0.isRace && $0.type == TYPE_RESP && $0.raceId == raceId
        }
    }
}

// MARK: - protocol operations

private struct OutsideState {
    var mode = -1
    var ancLevel = DEFAULT_LEVEL
    var ambientLevel = DEFAULT_LEVEL
    var adaptive = 0
}

private struct BatteryReading {
    /// role (0 = agent, 1 = partner) -> percentage, only when the headset reported 0..100
    var level: [Int: Int] = [:]
    /// role -> raw level byte, kept even when it is out of range (e.g. 0xC0)
    var raw: [Int: Int] = [:]
    /// role -> status byte of the 0x5D indication
    var flag: [Int: Int] = [:]
    /// role -> status byte of the 0x5B ack (2 = that role does not exist)
    var ack: [Int: Int] = [:]
}

private func readOutside(_ link: Link) -> OutsideState? {
    guard let frame = link.command(RID_GET_OUTSIDE), frame.status == 0, frame.raw.count >= 10 else {
        return nil
    }
    var state = OutsideState()
    state.mode = frame.u8(7)
    if frame.u8(8) >= 1 && frame.u8(8) <= 100 { state.ancLevel = frame.u8(8) }
    if frame.u8(9) >= 1 && frame.u8(9) <= 100 { state.ambientLevel = frame.u8(9) }
    return state
}

private func readAdaptive(_ link: Link) -> Int? {
    guard let frame = link.command(RID_GET_ADAPTIVE), frame.status == 0, frame.raw.count >= 8 else {
        return nil
    }
    return frame.u8(7) == 0 ? 0 : 1
}

private func readAgentIsRight(_ link: Link) -> Bool? {
    guard let frame = link.command(RID_GET_AGENT), frame.status == 0,
          frame.raw.count >= 8, frame.u8(7) <= 1 else { return nil }
    return frame.u8(7) == 1
}

/// TWS battery is two stage: a 0x5B ack, then an asynchronous 0x5D indication
/// carrying (role, level).  Both roles are requested first, then one window is
/// spent collecting the indications -- the partner one can be several seconds late,
/// and its level byte is not always a percentage.
private func readTwsBattery(_ link: Link, window: TimeInterval) -> BatteryReading {
    var reading = BatteryReading()
    let start = link.frames.count

    for role in [0, 1] {
        let mark = link.frames.count
        link.send(RID_GET_BATTERY, [UInt8(role)])
        let ack = link.waitFor(min(3, window), from: mark) {
            $0.isRace && $0.type == TYPE_RESP && $0.raceId == RID_GET_BATTERY
        }
        reading.ack[role] = ack?.status ?? -1
    }

    let expected = reading.ack.values.filter { $0 == 0 }.count
    guard expected > 0 else { return reading }

    let deadline = Date().addingTimeInterval(window)
    while true {
        for frame in link.frames[start...] where frame.isRace
            && frame.type == TYPE_IND && frame.raceId == RID_GET_BATTERY && frame.raw.count >= 9 {
            let role = frame.u8(7)
            guard (role == 0 || role == 1), reading.raw[role] == nil else { continue }
            reading.raw[role] = frame.u8(8)
            reading.flag[role] = frame.u8(6)
            if frame.u8(8) <= 100 { reading.level[role] = frame.u8(8) }
        }
        if reading.raw.count >= expected || Date() >= deadline { break }
        pump(0.05)
    }
    return reading
}

private func readCradle(_ link: Link) -> (level: Int, raw: Int)? {
    guard let frame = link.command(RID_GET_CRADLE), frame.status == 0, frame.raw.count >= 8 else {
        return nil
    }
    let raw = frame.u8(7)
    return (raw <= 100 ? raw : -1, raw)
}

/// LE Audio (LC3) switch, as Technics Audio Connect drives it:
/// `StageGetLeAudioSetting` reads payload[1], `StageSetLeAudioSetting` writes
/// one byte, 0 = off / 1 = on.  Audio Connect only offers it when the headset
/// reports `hasLeAudio`, and warns that switching drops the link and takes
/// some functions away -- both are the headset's own behaviour, not ours.
private func readLeAudio(_ link: Link) -> Int? {
    guard let frame = link.command(RID_GET_LE_AUDIO), frame.status == 0,
          frame.raw.count >= 8 else { return nil }
    return frame.u8(7)
}

/// The answer to a SET is a single byte that Audio Connect only logs
/// (`OnSetLeAudioSetting` re-reads the setting instead of checking it), so the
/// only meaningful confirmation is the read-back that follows.
private func setLeAudio(_ link: Link, _ on: Bool) -> Bool {
    link.command(RID_SET_LE_AUDIO, [on ? 1 : 0]) != nil
}

private func modeName(_ state: OutsideState) -> String {
    if state.mode == CTRL_ANC { return state.adaptive == 1 ? "adaptive" : "anc" }
    if state.mode == CTRL_AMBIENT { return "ambient" }
    if state.mode == CTRL_OFF { return "off" }
    return "unknown"
}

private func setMode(_ link: Link, target: String, state: OutsideState) -> Bool {
    let anc = state.ancLevel
    let ambient = state.ambientLevel

    func clearAdaptive() {
        if state.adaptive == 1 { _ = link.command(RID_SET_ADAPTIVE, [0]) }
    }

    switch target {
    case "off":
        clearAdaptive()
        return link.command(RID_SET_OUTSIDE, [UInt8(CTRL_OFF), UInt8(anc), UInt8(ambient)])?.status == 0
    case "anc", "noise", "降噪":
        clearAdaptive()
        return link.command(RID_SET_OUTSIDE, [UInt8(CTRL_ANC), UInt8(anc), UInt8(ambient)])?.status == 0
    case "ambient", "transparency", "通透":
        clearAdaptive()
        return link.command(RID_SET_OUTSIDE, [UInt8(CTRL_AMBIENT), UInt8(anc), UInt8(ambient)])?.status == 0
    case "adaptive", "自适应":
        let ok = link.command(RID_SET_OUTSIDE, [UInt8(CTRL_ANC), UInt8(anc), UInt8(ambient)])?.status == 0
        _ = link.command(RID_SET_ADAPTIVE, [1])
        return ok
    default:
        return false
    }
}

// MARK: - CLI

private func usage() -> Never {
    warn("""
    usage: az100ctl [options] <command>

    commands:
      devices                        list paired Bluetooth devices
      status                         mode + battery (left / right / case)
      mode                           report the current mode only
      mode off|anc|ambient|adaptive  switch the noise-cancelling mode
      battery                        report battery only
      leaudio                        report the LE Audio (LC3) switch
      leaudio on|off                 turn LE Audio on or off
      raw <hex bytes>                send a raw RACE frame and print the answers

    options:
      --addr <AA:BB:CC:DD:EE:FF>     headset address (default: first paired AZ100)
      --channel <n>                  RFCOMM channel (default: resolved through SDP)
      --timeout <seconds>            per-command timeout (default: 6)
      --debug                        trace every frame on stderr
    """)
    exit(1)
}

private func findDevice(_ address: String?) -> IOBluetoothDevice? {
    let paired = (IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice]) ?? []
    if let address = address {
        let wanted = address.replacingOccurrences(of: ":", with: "-").uppercased()
        return paired.first { ($0.addressString ?? "").uppercased() == wanted }
            ?? IOBluetoothDevice(addressString: wanted)
    }
    let matches = paired.filter {
        let name = ($0.name ?? "").uppercased()
        return name.contains("AZ100") || name.contains("AZ-100")
            || name.contains("EAH-") || name.contains("TECHNICS")
    }
    return matches.first { $0.isConnected() } ?? matches.first
}

private func parseOnOff(_ text: String) -> Bool? {
    switch text.lowercased() {
    case "on", "1", "true", "开", "开启": return true
    case "off", "0", "false", "关", "关闭": return false
    default: return nil
    }
}

private func hexToBytes(_ arguments: [String]) -> [UInt8]? {
    let joined = arguments.joined(separator: " ").replacingOccurrences(of: "0x", with: "")
    let parts = joined.split(whereSeparator: { $0 == " " || $0 == "," || $0 == ":" })
    var bytes: [UInt8] = []
    for part in parts {
        guard part.count <= 2, let value = UInt8(part, radix: 16) else { return nil }
        bytes.append(value)
    }
    return bytes.isEmpty ? nil : bytes
}

private func emit(_ key: String, _ value: Any) {
    writeLine(STDOUT_FILENO, "\(key)=\(value)")
}

// MARK: - entry point

// A closed stdout (the reader went away) must not kill us with SIGPIPE.
signal(SIGPIPE, SIG_IGN)

// Hard watchdog: never leave the terminal stuck if the headset stops answering.
DispatchQueue.global().asyncAfter(deadline: .now() + 120) {
    warn("az100ctl: watchdog timeout")
    exit(9)
}

var address: String? = ProcessInfo.processInfo.environment["AZ100_MAC"]
var forcedChannel: BluetoothRFCOMMChannelID? = nil
var commandTimeout: TimeInterval = 6
var debug = false
var positional: [String] = []

var index = 0
let arguments = Array(CommandLine.arguments.dropFirst())
while index < arguments.count {
    let argument = arguments[index]
    switch argument {
    case "--addr", "-d", "--device":
        index += 1
        guard index < arguments.count else { usage() }
        address = arguments[index]
    case "--channel", "-c":
        index += 1
        guard index < arguments.count, let value = UInt8(arguments[index]) else { usage() }
        forcedChannel = value
    case "--timeout", "-t":
        index += 1
        guard index < arguments.count, let value = Double(arguments[index]) else { usage() }
        commandTimeout = value
    case "--debug", "-v":
        debug = true
    case "-h", "--help":
        usage()
    default:
        positional.append(argument)
    }
    index += 1
}

guard let command = positional.first else { usage() }

/// Anything that is not a known verb would otherwise be treated as a mode name.
let knownCommands: Set<String> = [
    "devices", "status", "mode", "battery", "leaudio", "raw",
    "anc", "noise", "ambient", "transparency", "off", "adaptive", "toggle",
    "降噪", "通透", "关闭", "自适应",
]
guard knownCommands.contains(command) else {
    warn("az100ctl: unknown command '\(command)'")
    usage()
}

// `devices` needs no RFCOMM link.
if command == "devices" {
    let paired = (IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice]) ?? []
    for device in paired {
        let state = device.isConnected() ? "connected" : "paired"
        writeLine(STDOUT_FILENO, "\(device.name ?? "?")\t\(device.addressString ?? "?")\t\(state)")
    }
    exit(0)
}

let pumpTimer = Timer(timeInterval: 0.05, repeats: true) { _ in }
RunLoop.main.add(pumpTimer, forMode: .default)
defer { pumpTimer.invalidate() }

guard let device = findDevice(address) else {
    warn("az100ctl: no matching paired Bluetooth device" + (address.map { " for \($0)" } ?? ""))
    exit(2)
}
if debug { warn("device \(device.name ?? "?") \(device.addressString ?? "?") connected=\(device.isConnected())") }

guard let channelID = forcedChannel ?? Link.resolveChannel(device) else {
    warn("az100ctl: no Airoha SPP service (00000000-0000-0000-0099-AABBCCDDEEFF) on "
         + "\(device.name ?? "device")")
    exit(2)
}
if debug { warn("rfcomm channel \(channelID)") }

private let link = Link()
link.debug = debug
guard link.open(device, channelID: channelID) else {
    warn("az100ctl: could not open the RFCOMM channel to \(device.name ?? "device")")
    exit(3)
}
defer { link.close() }

let batteryWindow = max(3, commandTimeout)
var exitCode: Int32 = 0

func emitDevice(_ device: IOBluetoothDevice) {
    emit("DEVICE_NAME", device.name ?? "?")
    emit("ADDRESS", device.addressString ?? "?")
}

switch command {
case "status", "mode", "battery":
    guard var outside = readOutside(link) else {
        warn("az100ctl: no answer to the outside-control query")
        exit(4)
    }
    outside.adaptive = readAdaptive(link) ?? 0

    emitDevice(device)
    emit("MODE", modeName(outside))
    emit("MODE_BYTE", outside.mode)
    emit("ANC_LEVEL", outside.ancLevel)
    emit("AMBIENT_LEVEL", outside.ambientLevel)
    emit("ADAPTIVE", outside.adaptive)
    emit("LE_AUDIO", readLeAudio(link) ?? -1)

    if command != "mode" {
        let agentIsRight = readAgentIsRight(link) ?? false
        let reading = readTwsBattery(link, window: batteryWindow)
        var left = -1, right = -1
        var leftRaw = -1, rightRaw = -1
        for role in [0, 1] {
            guard let raw = reading.raw[role] else { continue }
            let side = agentIsRight ? 1 - role : role     // 0 = left, 1 = right
            if side == 0 { leftRaw = raw } else { rightRaw = raw }
            if let level = reading.level[role] {
                if side == 0 { left = level } else { right = level }
            }
        }
        let cradle = readCradle(link)
        emit("LEFT", left)
        emit("RIGHT", right)
        emit("CASE", cradle?.level ?? -1)
        emit("LEFT_RAW", leftRaw)
        emit("RIGHT_RAW", rightRaw)
        emit("CASE_RAW", cradle?.raw ?? -1)
        emit("AGENT", agentIsRight ? "right" : "left")
    }

case "leaudio":
    emitDevice(device)
    let wanted = positional.count > 1 ? positional[1] : nil
    if let wanted {
        guard let on = parseOnOff(wanted) else {
            warn("az100ctl: leaudio needs 'on' or 'off', got '\(wanted)'")
            exit(1)
        }
        guard setLeAudio(link, on) else {
            warn("az100ctl: no answer to the LE Audio command")
            exit(4)
        }
        emit("LE_AUDIO_REQUESTED", on ? 1 : 0)
        // The headset tears the link down to renegotiate, so the read-back is
        // best effort: no answer here is normal, not a failure.
        if let value = readLeAudio(link) {
            emit("LE_AUDIO", value)
        } else {
            emit("LE_AUDIO", on ? 1 : 0)
            emit("LE_AUDIO_LINK_DROPPED", 1)
        }
    } else {
        guard let value = readLeAudio(link) else {
            warn("az100ctl: no answer to the LE Audio query")
            exit(4)
        }
        emit("LE_AUDIO", value)
        emit("LE_AUDIO_NAME", value == 0 ? "off" : "on")
    }

case "raw":
    guard let bytes = hexToBytes(Array(positional.dropFirst())) else {
        warn("az100ctl: raw needs hex bytes, e.g. 05 5A 02 00 0A 00")
        exit(1)
    }
    guard link.write(bytes) else {
        warn("az100ctl: write failed")
        exit(3)
    }
    let start = link.frames.count
    let deadline = Date().addingTimeInterval(batteryWindow)
    var printed = 0
    while true {
        for frame in link.frames[start...] where frame.isRace
            && (frame.type == TYPE_RESP || frame.type == TYPE_IND) {
            writeLine(STDOUT_FILENO, hexString(frame.raw))
            printed += 1
        }
        if printed > 0 || Date() >= deadline { break }
        pump(0.05)
    }
    if printed == 0 { warn("az100ctl: no answer within \(batteryWindow)s") }

default:
    guard let state = readOutside(link) else {
        warn("az100ctl: no answer to the outside-control query")
        exit(4)
    }
    var current = state
    current.adaptive = readAdaptive(link) ?? 0

    var target = command
    if target == "toggle" {
        switch modeName(current) {
        case "off": target = "anc"
        case "anc", "adaptive": target = "ambient"
        default: target = "off"
        }
    }
    guard setMode(link, target: target, state: current) else {
        warn("az100ctl: the headset refused mode '\(target)'")
        exit(4)
    }
    // Read back, exactly like Audio Connect does after OnSetOutsideCtrl.
    var after = readOutside(link) ?? current
    after.adaptive = readAdaptive(link) ?? 0
    emitDevice(device)
    emit("MODE", modeName(after))
    emit("MODE_BYTE", after.mode)
    emit("ANC_LEVEL", after.ancLevel)
    emit("AMBIENT_LEVEL", after.ambientLevel)
    emit("ADAPTIVE", after.adaptive)
}

exit(exitCode)
AZ100_SWIFT_SOURCE

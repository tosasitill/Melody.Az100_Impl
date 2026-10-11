#!/bin/bash
#
# az100-menubar.sh — 构建并安装 AZ100 菜单栏小工具。
#
# 装好之后：耳机连上这台 Mac 时菜单栏自动出现「降噪 99%」这样的条目，点开可以
# 切换降噪 / 通透 / 关闭 / 自适应，也能看到左右耳和充电盒电量；耳机一断开，
# 条目自己消失，不占地方。
#
#   ./az100-menubar.sh              构建 + 安装到 ~/Applications + 启动
#   ./az100-menubar.sh build        只构建到 ~/Applications/AZ100.app
#   ./az100-menubar.sh install      同上（构建即安装）
#   ./az100-menubar.sh start        启动
#   ./az100-menubar.sh stop         停止
#   ./az100-menubar.sh restart      重启
#   ./az100-menubar.sh status       看是否在跑
#   ./az100-menubar.sh login        设为登录时自动启动
#   ./az100-menubar.sh logout       取消登录自启
#   ./az100-menubar.sh uninstall    停止 + 移除 App 和登录项
#
# 说明：
#   * 菜单栏 App 和 az100.sh 共用同一份协议实现——构建时会从 az100.sh 里抽出
#     内嵌的 Swift 源码编译成 az100ctl，放进 App bundle 的 Resources。
#   * 首次运行会弹一次蓝牙权限请求（因为这是独立签名的 App，不再借用终端的权限）。
#   * 想退出：菜单里选「退出」，或者 ./az100-menubar.sh stop。
#   * 调试：直接跑 ~/Applications/AZ100.app/Contents/MacOS/AZ100 看日志，
#     或 log stream --predicate 'process == "AZ100"'。

set -euo pipefail

VERSION="1.0.0"
SELF="${BASH_SOURCE[0]}"
[ -f "$SELF" ] || SELF="$0"
DIR="$(cd "$(dirname "$SELF")" && pwd)"

APP_NAME="AZ100"
BUNDLE_ID="com.tosasitill.az100.menubar"
INSTALL_DIR="${AZ100_INSTALL_DIR:-$HOME/Applications}"
APP_PATH="$INSTALL_DIR/$APP_NAME.app"
LAUNCH_AGENT="$HOME/Library/LaunchAgents/$BUNDLE_ID.plist"
BUILD_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/az100/build"

CLI_SCRIPT="$DIR/az100.sh"
APP_SOURCE="$DIR/az100-menubar.swift"

usage() {
    sed -n '2,/^set -euo pipefail$/p' "$SELF" | sed '$d' | sed 's/^#\{1,2\} \{0,1\}//'
    exit "${1:-0}"
}

die() { echo "$*" >&2; exit 1; }

require_swiftc() {
    command -v swiftc >/dev/null 2>&1 || die "需要 Xcode Command Line Tools：xcode-select --install"
}

# ---------------------------------------------------------------- 构建

# az100.sh 把协议实现内嵌在文件末尾的 heredoc 里，这里原样抽出来复用。
extract_core() {
    awk '/^: <<.AZ100_SWIFT_SOURCE.$/ { copy = 1; next } /^AZ100_SWIFT_SOURCE$/ { copy = 0 } copy' "$CLI_SCRIPT"
}

build() {
    require_swiftc
    [ -f "$CLI_SCRIPT" ] || die "找不到 $CLI_SCRIPT（本脚本要和 az100.sh 放在一起）"
    [ -f "$APP_SOURCE" ] || die "找不到 $APP_SOURCE"

    echo "==> 清理 $APP_PATH"
    rm -rf "$APP_PATH"
    mkdir -p "$APP_PATH/Contents/MacOS" "$APP_PATH/Contents/Resources" "$BUILD_DIR"

    echo "==> 从 az100.sh 抽取协议实现"
    extract_core > "$BUILD_DIR/az100ctl.swift"
    [ -s "$BUILD_DIR/az100ctl.swift" ] || die "抽取失败，az100.sh 结构可能变了"

    echo "==> 编译 az100ctl（协议客户端）"
    swiftc -O -o "$APP_PATH/Contents/Resources/az100ctl" "$BUILD_DIR/az100ctl.swift"

    echo "==> 编译 AZ100（菜单栏 App）"
    swiftc -O -o "$APP_PATH/Contents/MacOS/$APP_NAME" "$APP_SOURCE"

    echo "==> 写 Info.plist"
    cat > "$APP_PATH/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key><string>$APP_NAME</string>
    <key>CFBundleDisplayName</key><string>AZ100 耳机状态</string>
    <key>CFBundleExecutable</key><string>$APP_NAME</string>
    <key>CFBundleIdentifier</key><string>$BUNDLE_ID</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>$VERSION</string>
    <key>CFBundleVersion</key><string>$VERSION</string>
    <key>LSMinimumSystemVersion</key><string>12.0</string>
    <key>LSUIElement</key><true/>
    <key>NSHighResolutionCapable</key><true/>
    <key>NSBluetoothAlwaysUsageDescription</key>
    <string>用来读取并切换 Technics EAH-AZ100 的降噪模式和电量。</string>
    <key>NSBluetoothPeripheralUsageDescription</key>
    <string>用来读取并切换 Technics EAH-AZ100 的降噪模式和电量。</string>
</dict>
</plist>
PLIST

    # 临时签名：给 TCC 一个稳定的身份，否则每次重新构建都会被当成新 App 重新要权限。
    echo "==> 临时签名"
    codesign --force --sign - "$APP_PATH/Contents/Resources/az100ctl" >/dev/null 2>&1 || true
    codesign --force --sign - "$APP_PATH/Contents/MacOS/$APP_NAME" >/dev/null 2>&1 || true
    codesign --force --sign - "$APP_PATH" >/dev/null 2>&1 || true

    echo "==> 完成：$APP_PATH"
}

# ---------------------------------------------------------------- 运行控制

is_running() {
    pgrep -x "$APP_NAME" >/dev/null 2>&1
}

start_app() {
    [ -d "$APP_PATH" ] || die "还没构建：先跑 ./az100-menubar.sh build"
    if is_running; then
        echo "已经在运行（pid $(pgrep -x "$APP_NAME" | tr '\n' ' '))"
        return 0
    fi
    open "$APP_PATH"
    sleep 1
    if is_running; then
        echo "已启动。耳机连上后菜单栏就会出现条目。"
    else
        echo "启动失败。直接运行下面的命令看日志：" >&2
        echo "  \"$APP_PATH/Contents/MacOS/$APP_NAME\"" >&2
        return 1
    fi
}

stop_app() {
    if is_running; then
        pkill -x "$APP_NAME" || true
        echo "已停止。"
    else
        echo "本来就没在运行。"
    fi
}

status_app() {
    if [ -d "$APP_PATH" ]; then
        echo "App       已安装  $APP_PATH"
    else
        echo "App       未安装"
    fi
    if is_running; then
        echo "进程      运行中  pid $(pgrep -x "$APP_NAME" | tr '\n' ' ')"
    else
        echo "进程      未运行"
    fi
    if [ -f "$LAUNCH_AGENT" ]; then
        echo "登录自启  已开启"
    else
        echo "登录自启  未开启"
    fi
}

# ---------------------------------------------------------------- 登录自启

enable_login() {
    [ -d "$APP_PATH" ] || die "还没构建：先跑 ./az100-menubar.sh build"
    # Bootstrap starts the job immediately, so a manually launched copy would
    # end up sitting next to it and the menu bar would show two items.
    stop_app >/dev/null
    mkdir -p "$(dirname "$LAUNCH_AGENT")"
    cat > "$LAUNCH_AGENT" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key><string>$BUNDLE_ID</string>
    <key>ProgramArguments</key>
    <array><string>$APP_PATH/Contents/MacOS/$APP_NAME</string></array>
    <key>RunAtLoad</key><true/>
    <key>ProcessType</key><string>Interactive</string>
</dict>
</plist>
PLIST
    launchctl bootout "gui/$(id -u)/$BUNDLE_ID" >/dev/null 2>&1 || true
    # Bootstrap only loads it for *this* session.  It fails (EIO) from some
    # shell contexts, but that does not matter: launchd picks up
    # ~/Library/LaunchAgents at the next login regardless.
    if launchctl bootstrap "gui/$(id -u)" "$LAUNCH_AGENT" >/dev/null 2>&1; then
        sleep 1
        if is_running; then
            echo "已设为登录时自动启动，并且已经拉起来了（pid $(pgrep -x "$APP_NAME" | tr '\n' ' ')）。"
            return 0
        fi
        echo "已设为登录时自动启动，但这次没拉起来。" >&2
        return 1
    fi
    echo "已写入 $LAUNCH_AGENT"
    echo "下次登录会自动启动。本会话无法即时载入（launchctl bootstrap 被当前 shell 环境拒绝），"
    echo "要现在就跑请执行：./az100-menubar.sh start"
}

disable_login() {
    if [ -f "$LAUNCH_AGENT" ]; then
        launchctl bootout "gui/$(id -u)/$BUNDLE_ID" >/dev/null 2>&1 || true
        rm -f "$LAUNCH_AGENT"
        echo "已取消登录自启。"
    else
        echo "本来就没设登录自启。"
    fi
}

uninstall_app() {
    disable_login
    stop_app
    if [ -d "$APP_PATH" ]; then
        rm -rf "$APP_PATH"
        echo "已删除 $APP_PATH"
    fi
    rm -rf "$BUILD_DIR"
    echo "完成。"
}

# ---------------------------------------------------------------- 入口

command="${1:-all}"
[ $# -gt 0 ] && shift || true

case "$command" in
    build|install|all) build; [ "$command" = "build" ] || start_app ;;
    start)             start_app ;;
    stop)              stop_app ;;
    restart)           stop_app; start_app ;;
    status)            status_app ;;
    login)             enable_login ;;
    logout)            disable_login ;;
    uninstall|remove)  uninstall_app ;;
    help|-h|--help)    usage 0 ;;
    *)                 echo "未知命令：$command" >&2; usage 1 ;;
esac

#!/usr/bin/env python3
"""小区件的安全回归检查：发布版合并 manifest 的导出面与备份开关。

安全审计（2026-11）修复了两个 P2：
  1. 小组件 Provider 曾 exported=true，第三方应用可伪造广播篡改待办；
  2. allowBackup=true 会把待办/笔记放进系统云备份。
这个脚本在 CI 里对 release 合并后的 manifest 做断言，防止以后误改回去。

用法：python3 scripts/verify_release_manifest.py [manifest_path]
"""
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
DEFAULT_MANIFEST = (
    "app/build/intermediates/merged_manifests/release/"
    "processReleaseManifest/AndroidManifest.xml"
)
NON_EXPORTED_RECEIVERS = {
    "TodoWidgetProvider",
    "NotesWidgetProvider",
    "WidgetActionReceiver",
}


def main(manifest_path: str) -> int:
    try:
        root = ET.parse(manifest_path).getroot()
    except (OSError, ET.ParseError) as error:
        print(f"::error::cannot read merged manifest {manifest_path}: {error}")
        return 1

    application = root.find("application")
    if application is None:
        print(f"::error::no <application> element in {manifest_path}")
        return 1

    errors = []
    if application.get(ANDROID + "allowBackup") != "false":
        errors.append("android:allowBackup must be false")

    receivers = {
        (receiver.get(ANDROID + "name") or "").rsplit(".", 1)[-1]: receiver
        for receiver in application.findall("receiver")
    }
    for name in sorted(NON_EXPORTED_RECEIVERS):
        receiver = receivers.get(name)
        if receiver is None:
            errors.append(f"receiver {name} is missing from the release manifest")
        elif receiver.get(ANDROID + "exported") != "false":
            errors.append(f'receiver {name} must have android:exported="false"')

    if errors:
        for message in errors:
            print(f"::error::{message}")
        return 1

    print("Release manifest security properties OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else DEFAULT_MANIFEST))

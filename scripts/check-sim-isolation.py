"""核对模拟调试代码是否被隔离在 debug 变体。

为什么需要它:模拟注入器(SimulatedIphone / 接收器 / 叠加层 Activity)都放在
common/src/debug 源集,release 变体不编译该目录。理论上 release APK 里不该出现
任何一个符号 —— 这个脚本就是把该假设变成可复核的硬证据,而不是靠肉眼看。

审慎说明:不能用 `strings` 命令(Git Bash 精简版没带),也不能在 shell 里内联
长 python -c(沙箱拦截)。所以落成文件跑。
"""

import hashlib
import sys
import zipfile

KEYS = [
    "SimulatedIphone",
    "SimulateIphoneReceiver",
    "SimulatedClusterActivity",
    "StageLayout",
    "SimClusterOverlay",
]

APKS = [
    ("release", "src/mobile/build/outputs/apk/release/mobile-release.apk"),
    ("debug", "src/mobile/build/outputs/apk/debug/mobile-debug.apk"),
]


def main() -> int:
    bad = 0
    for tag, path in APKS:
        try:
            zf = zipfile.ZipFile(path)
        except FileNotFoundError:
            print(f"{tag}: 缺少 {path},跳过")
            continue
        names = sorted(n for n in zf.namelist() if n.endswith(".dex"))
        data = b"".join(zf.read(n) for n in names)
        digest = hashlib.sha256(data).hexdigest()
        print(f"{tag}: dex {len(names)} 个 / {len(data)} 字节  sha256={digest[:16]}")
        for key in KEYS:
            hits = data.count(key.encode())
            print(f"    {key}: {hits}")
            if tag == "release" and hits:
                bad += 1
    if bad:
        print(f"\n失败:release 里出现 {bad} 个模拟符号")
        return 1
    print("\n通过:release APK 不含任何模拟调试符号")
    return 0


if __name__ == "__main__":
    sys.exit(main())
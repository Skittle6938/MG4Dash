"""Check package, version and platform certificate before publishing a car APK."""
import argparse
import re
import subprocess
from pathlib import Path
from ci_version import read_version

CERTIFICATE = 'c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk')
    parser.add_argument('--build-tools', required=True)
    args = parser.parse_args()
    tools = Path(args.build_tools)
    suffix = '.bat' if (tools / 'apksigner.bat').exists() else ''
    signature = subprocess.check_output([str(tools / f'apksigner{suffix}'), 'verify', '--print-certs', args.apk], text=True)
    certificates = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)', signature)
    if certificates != [CERTIFICATE]:
        raise SystemExit('Unexpected APK signing certificate')
    suffix = '.exe' if (tools / 'aapt.exe').exists() else ''
    badging = subprocess.check_output([str(tools / f'aapt{suffix}'), 'dump', 'badging', args.apk], text=True, encoding='utf-8')
    code, name = read_version(Path('app/build.gradle.kts').read_text(encoding='utf-8-sig'))
    expected = f"package: name='app.mg4dash' versionCode='{code}' versionName='{name}'"
    if not badging.startswith(expected):
        raise SystemExit('APK package or version does not match the source')
    print(f'Verified app.mg4dash {name} ({code}), expected platform certificate')


if __name__ == '__main__':
    main()

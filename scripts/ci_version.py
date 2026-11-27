"""Validate Android versions, or prepare the next manually requested beta."""
import argparse
import re
import subprocess
from pathlib import Path

BUILD = Path('app/build.gradle.kts')
VERSION = re.compile(r'\d+\.\d+\.\d+(?:-beta[1-9]\d*)?')


def read_version(source):
    code = int(re.search(r'\bversionCode\s*=\s*(\d+)', source)[1])
    name = re.search(r'\bversionName\s*=\s*"([^"]+)"', source)[1]
    if not VERSION.fullmatch(name):
        raise SystemExit(f'Invalid version name: {name}')
    return code, name


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', help='Compare against this git revision')
    parser.add_argument('--prepare-beta', help='Set the requested beta and increment versionCode')
    args = parser.parse_args()
    source = BUILD.read_text(encoding='utf-8-sig')
    code, name = read_version(source)
    if args.prepare_beta:
        requested = args.prepare_beta
        # A beta (1.7.4-beta15) or, for an official release, a plain version (1.8.0)
        if not re.fullmatch(r'\d+\.\d+\.\d+(?:-beta[1-9]\d*)?', requested):
            raise SystemExit('Expected a version such as 1.7.4-beta15 or 1.8.0')
        if requested != name:
            parts = lambda v: tuple(map(int, re.findall(r'\d+', v)))
            if '-beta' in name and '-beta' in requested and parts(requested) <= parts(name):
                raise SystemExit('The beta version must increase')
            if '-beta' in name and '-beta' not in requested and parts(requested) < parts(name)[:3]:
                raise SystemExit('An official release must not be older than the current beta')
            if '-beta' not in name and parts(requested)[:3] <= parts(name)[:3]:
                raise SystemExit('A beta after a stable release must use a newer base version')
            source = re.sub(r'(\bversionCode\s*=\s*)\d+', rf'\g<1>{code + 1}', source)
            source = re.sub(r'(\bversionName\s*=\s*)"[^"]+"', rf'\g<1>"{requested}"', source)
            BUILD.write_text(source, encoding='utf-8-sig', newline='\n')
            code, name = read_version(source)
    if args.base and not set(args.base) == {'0'}:
        old = subprocess.check_output(['git', 'show', f'{args.base}:app/build.gradle.kts']).decode('utf-8-sig')
        old_code, old_name = read_version(old)
        if code < old_code or (name != old_name and code <= old_code):
            raise SystemExit('A version change must increase versionCode')
        if name == old_name and code != old_code:
            raise SystemExit('A versionCode change must also change versionName')
    print(f'{name} (versionCode {code})')


if __name__ == '__main__':
    main()

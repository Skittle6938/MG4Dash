"""Write test results, Android lint findings and APK sizes to the Actions summary."""
import argparse
from collections import Counter
import os
import xml.etree.ElementTree as ET
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-lint', action='store_true')
    args = parser.parse_args()
    lines = ['## Validation', '', '| Test suite | Tests | Failed | Errors | Skipped |',
             '| --- | ---: | ---: | ---: | ---: |']
    failed = False
    problems = []
    for module, task in [('app', 'testEmulatorDebugUnitTest'), ('rescue', 'testDebugUnitTest')]:
        reports = list(Path(f'{module}/build/test-results/{task}').glob('TEST-*.xml'))
        totals = dict.fromkeys(['tests', 'failures', 'errors', 'skipped'], 0)
        for report in reports:
            suite = ET.parse(report).getroot()
            for key in totals:
                totals[key] += int(suite.get(key, 0))
            for case in suite.findall('testcase'):
                for kind in ('failure', 'error'):
                    problem = case.find(kind)
                    if problem is not None:
                        message = (problem.get('message') or problem.text or '')[:1500]
                        print(f'{report.name}: {case.get("name")}: {message}')
                        problems.extend(['', f'**{report.name} / {case.get("name")}**', '', '```text', message, '```', ''])
        lines.append(f'| {module} | ' + ' | '.join(str(value) for value in totals.values()) + ' |')
        if not reports:
            problems.append(f'\n{module}: no test report produced.\n')
        failed = failed or not reports or totals['failures'] > 0 or totals['errors'] > 0 or totals['tests'] == 0
    lines.extend(problems)
    errors = warnings = 0
    findings = Counter()
    lint_reports = list(Path('app/build/reports').glob('lint-results-carRelease.xml'))
    for report in lint_reports:
        for issue in ET.parse(report).getroot().findall('issue'):
            severity = issue.get('severity')
            errors += severity in ('Error', 'Fatal')
            warnings += severity == 'Warning'
            findings[(severity, issue.get('id'))] += 1
            if severity in ('Error', 'Fatal'):
                print(f'{severity} {issue.get("id")}: {issue.get("message")}')
    lines.extend(['', f'Android lint: **{errors} errors, {warnings} warnings**.', ''])
    if findings:
        lines.extend(['', '| Severity | Rule | Count |', '| --- | --- | ---: |'])
        for (severity, rule), count in sorted(findings.items()):
            lines.append(f'| {severity} | `{rule}` | {count} |')
    lines.extend(['', '### APKs', ''])
    for apk in sorted(Path('app/build/outputs/apk').glob('**/*.apk')):
        lines.append(f'- `{apk.name}` — {apk.stat().st_size / 1024 / 1024:.2f} MiB')
    text = '\n'.join(lines) + '\n'
    print(text)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as output:
            output.write(text)
    if args.check_lint:
        failed = failed or errors > 0 or not lint_reports
    if failed:
        raise SystemExit('Tests or Android lint failed, or a required report is missing')


if __name__ == '__main__':
    main()

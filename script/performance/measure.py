#!/usr/bin/env python3
"""Self-hosted, synthetic keyboard benchmark. Requires an installed development APK."""
import argparse
import hashlib
import json
import re
import shlex
import statistics
import subprocess
import tarfile
import tempfile
import uuid
from pathlib import Path


def statistics_ms(samples):
    if not samples:
        raise ValueError('No samples')
    values = sorted(samples)
    import math
    return {'median_ms': statistics.median(values) / 1e6,
            'p95_ms': values[math.ceil(len(values) * .95) - 1] / 1e6,
            'n': len(values)}


def compare(baseline, candidate):
    reports = [[json.loads(Path(p).read_text()) for p in paths] for paths in (baseline, candidate)]
    reference = reports[0][0]
    for group in reports:
        for report in group:
            if report.get('status') != 'PASS':
                raise ValueError('Incomplete benchmark')
            for key in ('device', 'sdk', 'debuggable', 'active_key_count', 'custom_mode', 'width_px'):
                if report[key] != reference[key]:
                    raise ValueError('Incomparable environment: ' + key)
    output = {}
    for metric in ('model_docked', 'model_floating', 'full_rebuild', 'refresh_to_ondraw'):
        item = {}
        for label, group in zip(('baseline', 'candidate'), reports):
            summaries = [statistics_ms(r[metric]['raw_ns']) for r in group]
            item[label] = {'median_of_run_medians_ms': statistics.median(s['median_ms'] for s in summaries),
                           'run_medians_ms': [s['median_ms'] for s in summaries],
                           'run_p95_ms': [s['p95_ms'] for s in summaries],
                           'total_samples': sum(s['n'] for s in summaries)}
        old = item['baseline']['median_of_run_medians_ms']
        new = item['candidate']['median_of_run_medians_ms']
        item['change_percent'] = (new / old - 1) * 100 if old else None
        output[metric] = item
    output['notice'] = 'Debug same-device component comparison; no claim about touch-to-display latency or Release performance.'
    return output


def capture(args):
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,30}', args.label):
        raise ValueError('Invalid report label')
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_.]+', args.package):
        raise ValueError('Invalid package')
    args.output.mkdir(parents=True, exist_ok=True)
    def adb(*items, **kwargs):
        return subprocess.run(['adb', '-s', args.serial, *items], check=True, **kwargs)
    def own(command, **kwargs):
        return adb('shell', 'run-as', args.package, 'sh', '-c', shlex.quote(command), **kwargs)
    original = adb('shell', 'settings', 'get', 'secure', 'default_input_method', capture_output=True, text=True).stdout.strip()
    enabled = adb('shell', 'ime', 'list', '-s', capture_output=True, text=True).stdout.splitlines()
    ime = args.package + '/com.osfans.trime.ime.core.TrimeInputMethodService'
    private = Path(tempfile.mkdtemp(prefix='trime-perf-private-'))
    backup = private / 'configuration.tar'
    adb('shell', 'am', 'force-stop', args.package)
    with backup.open('wb') as stream:
        adb('exec-out', 'run-as', args.package, 'tar', '-cf', '-', 'files', 'shared_prefs', stdout=stream)
    backup.chmod(0o600)
    with tarfile.open(backup) as archive:
        for member in archive:
            parts = Path(member.name).parts
            if not parts or parts[0] not in ('files', 'shared_prefs') or '..' in parts or member.name.startswith('/') or not (member.isfile() or member.isdir()):
                raise ValueError('Unsafe backup entry')
    try:
        adb('shell', 'ime', 'enable', ime, stdout=subprocess.DEVNULL)
        adb('shell', 'ime', 'set', ime, stdout=subprocess.DEVNULL)
        for run in range(1, args.runs + 1):
            label = args.label + str(run)
            execution = adb('shell', 'am', 'instrument', '-w', '-e', 'label', label,
                            args.package + '/com.osfans.trime.benchmark.PerformanceRunner', capture_output=True, text=True)
            (args.output / (label + '.instrument.txt')).write_text(execution.stdout + execution.stderr)
            data = adb('exec-out', 'run-as', args.package, 'cat', 'cache/performance-' + label + '.json', capture_output=True).stdout
            report = json.loads(data)
            (args.output / (label + '.json')).write_bytes(data)
            if report['status'] != 'PASS' or 'result=PASS' not in execution.stdout:
                raise RuntimeError('Failed run: ' + label)
            print(label, 'PASS', flush=True)
        startups = []
        for iteration in range(6):
            adb('shell', 'am', 'force-stop', args.package)
            result = adb('shell', 'am', 'start', '-W', '-n', args.package + '/com.osfans.trime.ui.main.MainActivity', capture_output=True, text=True).stdout
            row = {key: int(value) for key, value in re.findall(r'(ThisTime|TotalTime|WaitTime): (\d+)', result)}
            if 'TotalTime' not in row:
                raise RuntimeError('Missing startup timing')
            if iteration:
                startups.append(row)
        (args.output / (args.label + '-startup.json')).write_text(json.dumps(startups, indent=2))
    finally:
        # Stop writers before restoring preferences and stats. Keep both backups on any failure.
        adb('shell', 'ime', 'set', original, stdout=subprocess.DEVNULL)
        adb('shell', 'am', 'force-stop', args.package)
        stage = 'cache/perf-restore-' + uuid.uuid4().hex
        own('mkdir -p ' + stage)
        with backup.open('rb') as stream:
            own('tar -xf - -C ' + stage, stdin=stream)
        own('mv files ' + stage + '/posttest-files && mv shared_prefs ' + stage + '/posttest-prefs && mv ' + stage + '/files files && mv ' + stage + '/shared_prefs shared_prefs')
        with tarfile.open(backup) as archive:
            expected = {m.name: hashlib.sha256(archive.extractfile(m).read()).hexdigest() for m in archive if m.isfile()}
        command = 'sha256sum -b -- ' + ' '.join(shlex.quote(path) for path in expected)
        hashes = own(command, capture_output=True, text=True).stdout.splitlines()
        actual = dict(zip(expected, hashes))
        if len(hashes) != len(expected):
            raise RuntimeError('Incomplete restore verification; backup kept at ' + str(private))
        if expected != actual:
            raise RuntimeError('Restore verification failed; private backup kept at ' + str(private))
        if ime not in enabled:
            adb('shell', 'ime', 'disable', ime, stdout=subprocess.DEVNULL)
        # stage contains only this run's generated restore/post-test directories.
        own('rm -rf -- ' + shlex.quote(stage))
        backup.unlink()
        private.rmdir()
        print('Original IME, configuration and statistics restored and hash-verified.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    measure = commands.add_parser('capture')
    measure.add_argument('--serial', required=True)
    measure.add_argument('--package', default='com.osfans.trime.debug.v2')
    measure.add_argument('--label', required=True)
    measure.add_argument('--runs', type=int, choices=range(1, 11), default=3)
    measure.add_argument('--output', type=Path, required=True)
    comparison = commands.add_parser('compare')
    comparison.add_argument('--baseline', nargs='+', required=True)
    comparison.add_argument('--candidate', nargs='+', required=True)
    comparison.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'capture':
        capture(args)
    else:
        args.output.write_text(json.dumps(compare(args.baseline, args.candidate), ensure_ascii=False, indent=2))
        print(args.output)

if __name__ == '__main__':
    main()

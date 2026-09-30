#!/usr/bin/env python3
"""Skip re-testing a main push whose code already passed a full main build.

Two trees count as the same when they differ only in release notes/docs or in the
app version lines (versionCode/versionName, package versions). A version bump after
a green build therefore only builds, signs and releases; any code change runs every
check. Exit status 0 means "already tested" and writes reused/browser_reused/tested.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.request

VERSION_FILES = {'app/build.gradle', 'package.json', 'package-lock.json'}


def git(*args):
    return subprocess.check_output(['git', *args])


def without_version(path, content):
    text = content.decode('utf-8')
    if path == 'app/build.gradle':
        return re.sub(r"(?m)^\s*(versionCode\s+\d+|versionName\s+'[^']*')\s*$\n?", '', text).encode()
    data = json.loads(text)
    data.pop('version', None)
    if path == 'package-lock.json':
        data.get('packages', {}).get('', {}).pop('version', None)
    return json.dumps(data, sort_keys=True).encode()


def code_fingerprint(tree, read_blob):
    """Hash of a `git ls-tree -r -z` listing, ignoring docs and version lines."""
    records = []
    for entry in tree.split(b'\0'):
        if not entry:
            continue
        meta, path = entry.split(b'\t', 1)
        name = path.decode('utf-8')
        if name.startswith('docs/') or ('/' not in name and name.endswith('.md')):
            continue
        if name in VERSION_FILES:
            blob = meta.split()[2].decode()
            entry = meta.split()[0] + b' ' + hashlib.sha256(without_version(name, read_blob(blob))).hexdigest().encode() + b'\t' + path
        records.append(entry)
    return hashlib.sha256(b'\0'.join(sorted(records))).hexdigest()


def read_blob(blob):
    return git('cat-file', '-p', blob)


def api(path):
    request = urllib.request.Request('https://api.github.com/repos/' + os.environ['GITHUB_REPOSITORY'] + path,
        headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'], 'Accept': 'application/vnd.github+json'})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def tested_run():
    current = code_fingerprint(git('ls-tree', '-r', '-z', 'HEAD'), read_blob)
    runs = api('/actions/workflows/android.yml/runs?branch=main&event=push&status=success&per_page=20')['workflow_runs']
    for run in runs:
        sha = run.get('head_sha', '')
        # Every successful main push either ran all checks or matched a tree that did.
        if str(run['id']) == os.environ.get('GITHUB_RUN_ID') or run.get('head_branch') != 'main' or not re.fullmatch('[a-f0-9]{40}', sha):
            continue
        subprocess.run(['git', 'fetch', '--no-tags', '--depth=1', 'origin', sha], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if code_fingerprint(git('ls-tree', '-r', '-z', sha), read_blob) == current:
            return run
    return None


def main():
    run = tested_run()
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        values = {'reused': 'true', 'browser_reused': 'true', 'tested': 'true'} if run else {'tested': 'false'}
        for key, value in values.items():
            output.write(f'{key}={value}\n')
    if not run:
        print('Code changed since the last green main build; running every check.')
        return 1
    print('Only version or docs changed since a green main build; build and release only: ' + run['html_url'])
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
        summary.write('Code identical to a green main build (only version/docs changed): ' + run['html_url'] + '\n\nTests were not repeated. The APK is built, signed and released.\n')
    return 0


if __name__ == '__main__':
    sys.exit(main())

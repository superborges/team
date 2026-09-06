#!/usr/bin/env python3
"""Bounded local read-only HTTP bursts; deliberately not a production capacity benchmark."""
import concurrent.futures
import datetime as dt
import importlib.util
import json
import math
import os
from pathlib import Path
import subprocess
import threading
import time
import urllib.error
import urllib.request
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('local_api_verification', ROOT / 'scripts/verify-local-api.py')
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)
Client, sql = helper.Client, helper.sql
BASE = 'http://127.0.0.1:8080'
RUN = dt.datetime.now(ZoneInfo('Asia/Shanghai')).strftime('%Y%m%d-%H%M%S')

def cookies(client):
    jar = next(handler.cookiejar for handler in client.opener.handlers if isinstance(handler, urllib.request.HTTPCookieProcessor))
    return '; '.join(cookie.name + '=' + cookie.value for cookie in jar)

def percentile(values, fraction):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]

def burst(name, path, cookie, concurrency, expected_shape):
    samples = 100
    barrier = threading.Barrier(concurrency + 1)
    def request(index):
        if index < concurrency:
            barrier.wait(timeout=10)
        started = time.perf_counter()
        status, size, error = 0, 0, None
        try:
            # A fresh connection per request; immutable pre-authenticated Cookie avoids a shared mutable CookieJar.
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            req = urllib.request.Request(BASE + path, headers={'Cookie': cookie, 'Accept': 'application/json'}, method='GET')
            with opener.open(req, timeout=20) as response:
                payload = response.read()
                status, size = response.status, len(payload)
                value = json.loads(payload)
                if not expected_shape(value):
                    error = 'Unexpected response shape'
        except urllib.error.HTTPError as exception:
            status = exception.code
            error = exception.read().decode()[:300]
        except Exception as exception:
            error = type(exception).__name__ + ': ' + str(exception)
        return {'index': index, 'status': status, 'milliseconds': (time.perf_counter() - started) * 1000, 'bytes': size, 'error': error}
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = [pool.submit(request, index) for index in range(samples)]
        barrier.wait(timeout=10)
        results = [future.result() for future in futures]
    duration = time.perf_counter() - started
    successful = [r for r in results if r['status'] == 200 and r['error'] is None]
    timings = [r['milliseconds'] for r in results]
    summary = {'name': name, 'path': path, 'samples': samples, 'concurrency': concurrency, 'successes': len(successful), 'successRatePercent': round(len(successful) * 100 / samples, 2), 'p50Ms': round(percentile(timings, .50), 2), 'p95Ms': round(percentile(timings, .95), 2), 'maxMs': round(max(timings), 2), 'durationSeconds': round(duration, 3), 'responseBytes': sum(r['bytes'] for r in results), 'errors': [r for r in results if r['error'] is not None], 'samplesMs': [round(value, 3) for value in timings]}
    print(json.dumps({k: v for k, v in summary.items() if k != 'samplesMs'}, ensure_ascii=False), flush=True)
    return summary

def main():
    anonymous, employee, admin = Client(), Client(), Client()
    assert anonymous.request('GET', '/actuator/health')['status'] == 'UP'
    mode = anonymous.request('GET', '/api/v1/auth/status')
    assert mode['mode'] == 'local' and mode['localLoginEnabled'] is True, mode
    assert sql("SELECT employee_no,wecom_userid FROM app_user WHERE id=4") == [['00123', 'local-admin']]
    assert employee.login('98123')['id'] == '1'
    assert admin.login('00123')['id'] == '4'
    today = dt.datetime.now(ZoneInfo('Asia/Shanghai')).date()
    prior = sql("SELECT MAX(work_date) FROM day_record WHERE user_id=1 AND work_date<=DATE(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00'))")[0][0]
    if prior == 'NULL':
        raise AssertionError('Need an existing employee day; this read-only test will not create one')
    dt.date.fromisoformat(prior)
    count = sql("""SELECT (SELECT COUNT(*) FROM app_user),(SELECT COUNT(*) FROM reporting_enrollment WHERE voided=FALSE),
       (SELECT COUNT(*) FROM day_record),(SELECT COUNT(*) FROM time_entry_revision),(SELECT COUNT(*) FROM onsite_day_revision),
       (SELECT COUNT(*) FROM work_item),(SELECT COUNT(*) FROM monthly_snapshot_row)""")[0]
    processes = [line.strip() for line in subprocess.check_output(['ps', '-axo', 'pid=,command='], text=True).splitlines() if 'java -jar ' in line and '--spring.profiles.active=local,api' in line]
    assert len(processes) == 1, processes
    report = {'run': RUN, 'startedAt': dt.datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(), 'apiProcess': processes[0], 'cpuLogicalCount': os.cpu_count(), 'dataset': dict(zip(['users', 'enrollments', 'days', 'timeRevisions', 'onsiteRevisions', 'workItems', 'snapshotRows'], map(int, count))), 'sessionModel': 'one pre-authenticated immutable cookie per identity; login excluded; fresh HTTP connection per request', 'stages': [], 'downgrades': []}
    stages = [('/me', '/api/v1/me', cookies(employee), lambda v: v.get('id') == '1'),
              ('employee-day', '/api/v1/days/' + prior, cookies(employee), lambda v: isinstance(v.get('entries'), list) and 'validation' in v),
              ('admin-project-costs', '/api/v1/reports/project-costs?from=' + today.replace(day=1).isoformat() + '&to=' + today.isoformat() + '&mode=CURRENT', cookies(admin), lambda v: v.get('kind') == 'project-costs' and isinstance(v.get('rows'), list))]
    concurrency = 100
    for name, path, cookie, shape in stages:
        summary = burst(name, path, cookie, concurrency, shape)
        report['stages'].append(summary)
        if summary['successRatePercent'] <= 90 or summary['p95Ms'] > 10000:
            concurrency = 20
            report['downgrades'].append({'afterStage': name, 'nextConcurrency': 20, 'reason': 'Error rate at least 10% or p95 above 10 seconds; avoid sustained overload'})
        assert anonymous.request('GET', '/actuator/health')['status'] == 'UP'
    report['healthAfter'] = anonymous.request('GET', '/actuator/health')
    report['finishedAt'] = dt.datetime.now(ZoneInfo('Asia/Shanghai')).isoformat()
    report['result'] = 'PASS' if all(stage['successRatePercent'] == 100 for stage in report['stages']) else 'OBSERVED_FAILURES'
    file = ROOT / '.local' / ('local-load-verification-' + RUN + '.json')
    file.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print('RESULT_FILE', file, flush=True)

if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Exercise the explicit local seed environment through real HTTP + read-only SQL."""
import concurrent.futures
import copy
import datetime as dt
import http.cookiejar
import json
import pathlib
import subprocess
import sys
import threading
import urllib.error
import urllib.request
import uuid
from zoneinfo import ZoneInfo

ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = 'http://127.0.0.1:8080'
NOW = dt.datetime.now(ZoneInfo('Asia/Shanghai'))
RUN = NOW.strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:6]
MARKER = '本地API验证 ' + RUN
RESULTS = []
DATES = []


class Client:
    def __init__(self):
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = None

    def request(self, method, path, body=None, *, csrf=True, key=None, expected=200):
        headers = {'Accept': 'application/json'}
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        if body is not None:
            headers['Content-Type'] = 'application/json'
        if csrf and self.csrf and method not in ('GET', 'HEAD'):
            headers[self.csrf['headerName']] = self.csrf['token']
        if key:
            headers['Idempotency-Key'] = key
        req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
        try:
            response = self.opener.open(req, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read().decode()
            value = json.loads(raw) if raw else None
            status = response.status
        if expected is not None and status != expected:
            raise AssertionError(f'{method} {path}: expected {expected}, got {status}: {value}')
        return (status, value) if expected is None else value

    def login(self, employee_no):
        self.csrf = self.request('GET', '/api/v1/auth/csrf')
        me = self.request('POST', '/api/v1/auth/local-login', {'employeeNo': employee_no})
        self.csrf = self.request('GET', '/api/v1/auth/csrf')
        return me

    def day(self, date):
        return self.request('GET', '/api/v1/days/' + date)

    def save(self, date, value, **kwargs):
        return self.request('PUT', '/api/v1/days/' + date, value, **kwargs)


def sql(query):
    if not query.lstrip().upper().startswith(('SELECT ', 'SELECT\n')):
        raise ValueError('Verification SQL is read-only')
    command = ['docker', 'compose', '-f', str(ROOT / 'deploy/compose.local.yml'), 'exec', '-T', 'mysql',
               'sh', '-c', 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --protocol=TCP --default-character-set=utf8mb4 -h 127.0.0.1 -u "$MYSQL_USER" "$MYSQL_DATABASE" -N -B']
    result = subprocess.run(command, input=query + ';\n', text=True, capture_output=True, check=True, timeout=20)
    return [line.split('\t') for line in result.stdout.strip().splitlines()]


def database_snapshot(date):
    # Dates originate only from datetime.date objects / validated API selection below.
    dt.date.fromisoformat(date)
    where = f"d.user_id=1 AND d.work_date='{date}'"
    row = sql(f"""SELECT
      (SELECT COUNT(*) FROM day_record d WHERE {where}),
      COALESCE((SELECT row_version FROM day_record d WHERE {where}),-1),
      (SELECT COUNT(*) FROM time_entry e JOIN day_record d ON d.id=e.day_id WHERE {where}),
      (SELECT COUNT(*) FROM time_entry_revision r JOIN time_entry e ON e.id=r.entry_id JOIN day_record d ON d.id=e.day_id WHERE {where}),
      (SELECT COUNT(*) FROM onsite_day_revision r JOIN onsite_day o ON o.id=r.onsite_id JOIN day_record d ON d.id=o.day_id WHERE {where}),
      (SELECT COUNT(*) FROM audit_event a JOIN day_record d ON CAST(d.id AS CHAR)=a.object_id WHERE a.object_type='DAY' AND {where}),
      (SELECT COUNT(*) FROM command_receipt WHERE actor_id=1 AND command_type='SAVE_DAY:{date}')""")[0]
    return [int(value) for value in row]


def no_money(value):
    denied = {'amount', 'dailyRate', 'laborCost', 'onsiteCost', 'totalCost', 'costSnapshot', 'salary'}
    if isinstance(value, dict):
        assert not denied.intersection(value), f'Money fields leaked: {denied.intersection(value)}'
        for child in value.values():
            no_money(child)
    elif isinstance(value, list):
        for child in value:
            no_money(child)


def check(name, test):
    started = dt.datetime.now()
    try:
        evidence = test()
    except Exception as error:
        RESULTS.append({'name': name, 'status': 'FAIL', 'evidence': str(error)})
        print('FAIL:', name, str(error), flush=True)
        raise
    RESULTS.append({'name': name, 'status': 'PASS', 'evidence': evidence,
                    'seconds': round((dt.datetime.now() - started).total_seconds(), 3)})
    print('PASS:', name, evidence, flush=True)


def entry(hours='4', suffix='保存并读回'):
    return {'id': None, 'workItemId': '1', 'kind': 'WORK', 'hours': hours,
            'content': MARKER + ' · ' + suffix, 'redReason': '本地自动验证数据'}


def free_day(client):
    for offset in range(1, 91):
        date = (NOW.date() + dt.timedelta(days=offset)).isoformat()
        if date in DATES:
            continue
        day = client.day(date)
        if day['version'] == 0 and not day['entries'] and day['onsite'] is None and day['editable']:
            DATES.append(date)
            return date
    raise AssertionError('No unused local test date in the next 90 days; existing records were preserved')


def main():
    anonymous, employee, employee2, admin = Client(), Client(), Client(), Client()

    def local_guard():
        assert anonymous.request('GET', '/actuator/health')['status'] == 'UP'
        status = anonymous.request('GET', '/api/v1/auth/status')
        assert status['mode'] == 'local' and status['localLoginEnabled'] is True, status
        assert sql("SELECT id,employee_no,wecom_userid FROM app_user WHERE id IN (1,4) ORDER BY id") == [
            ['1', '98123', 'local-employee'], ['4', '00123', 'local-admin']], 'Expected local seed identities are absent'
        return 'Local mode + seed identities + health UP confirmed; no production endpoint accepted'
    check('01 本地环境隔离', local_guard)

    def anonymous_denied():
        body = anonymous.request('GET', '/api/v1/me', expected=401)
        assert body['code'] == 'UNAUTHENTICATED', body
        return 'GET /me → 401 UNAUTHENTICATED'
    check('02 未登录访问拒绝', anonymous_denied)

    def csrf_denied():
        anonymous.request('POST', '/api/v1/auth/local-login', {'employeeNo': '98123'}, csrf=False, expected=403)
        return 'POST local-login without CSRF → 403'
    check('03 CSRF 校验', csrf_denied)

    def identities():
        assert employee.login('98123')['id'] == '1'
        assert employee2.login('98123')['id'] == '1'
        me = admin.login('00123')
        assert me['id'] == '4' and me['employeeNo'] == '00123' and me['canManage'] is True, me
        return 'Two independent employee sessions; administrator 00123 retains leading zero and stable ID 4'
    check('04 本地会话与前导零工号', identities)

    def permissions():
        employee.request('GET', '/api/v1/master/users', expected=403)
        employee.request('GET', '/api/v1/master/rates', expected=403)
        no_money(employee.request('GET', '/api/v1/me'))
        no_money(employee.request('GET', '/api/v1/catalog'))
        return 'Employee master users/rates both 403; identity/catalog contain no money fields'
    check('05 员工主数据权限与字段隔离', permissions)

    date = free_day(employee)
    saved = {}
    def save_readback():
        before = employee.day(date)
        value = {'expectedVersion': before['version'], 'entries': [entry()],
                 'onsite': {'id': None, 'workItemId': '1', 'reason': MARKER + ' 现场自然日'}}
        result = employee.save(date, value)
        reread = employee.day(date)
        assert result == reread and result['version'] == before['version'] + 1
        assert result['totalMinutes'] == 240 and result['actualMinutes'] == 240 and result['onsite'] is not None
        assert result['entries'][0]['content'].startswith(MARKER)
        no_money(result)
        saved.update(result)
        return f'{date}: 4h draft + independent onsite persisted, version {result["version"]}'
    check('06 真实保存与读回', save_readback)

    def limit_rollback():
        before, counts = employee.day(date), database_snapshot(date)
        bad = {'expectedVersion': before['version'], 'entries': [entry('16', '回滚甲'), entry('9', '回滚乙')], 'onsite': None}
        body = employee.save(date, bad, expected=422)
        assert body['code'] == 'DAY_LIMIT', body
        assert employee.day(date) == before and database_snapshot(date) == counts
        return '25h → 422 DAY_LIMIT; day version, revision, onsite, audit and receipt counts unchanged'
    check('07 24 小时限制与事务回滚', limit_rollback)

    def concurrent_write():
        before = employee.day(date)
        values = []
        for label in ['并发甲', '并发乙']:
            item = entry('5', label)
            item['id'] = before['entries'][0]['id']
            values.append({'expectedVersion': before['version'], 'entries': [item],
                           'onsite': {k: before['onsite'][k] for k in ['id', 'workItemId', 'reason']}})
        barrier = threading.Barrier(2)
        def write(client, value):
            barrier.wait(timeout=10)
            return client.save(date, value, expected=None)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(write, employee, values[0]), pool.submit(write, employee2, values[1])]
            results = [future.result() for future in futures]
        assert sorted(status for status, _ in results) == [200, 409], results
        rejected = next(body for status, body in results if status == 409)
        assert rejected['code'] == 'VERSION_CONFLICT', rejected
        assert employee.day(date)['version'] == before['version'] + 1
        return 'Two distinct sessions with one expectedVersion → exactly one 200 and one 409 VERSION_CONFLICT'
    check('08 同日双会话并发', concurrent_write)

    def idempotency():
        before = employee.day(date)
        item = entry('6', '幂等重放')
        item['id'] = before['entries'][0]['id']
        value = {'expectedVersion': before['version'], 'entries': [item],
                 'onsite': {k: before['onsite'][k] for k in ['id', 'workItemId', 'reason']}}
        key = 'verify-' + RUN
        first = employee.save(date, value, key=key)
        counts = database_snapshot(date)
        replay = employee.save(date, value, key=key)
        assert replay == first and database_snapshot(date) == counts
        other = copy.deepcopy(value)
        other['entries'][0]['hours'] = '7'
        conflict = employee.save(date, other, key=key, expected=409)
        assert conflict['code'] == 'IDEMPOTENCY_CONFLICT', conflict
        assert employee.day(date) == first and database_snapshot(date) == counts
        return 'Same key/body replays original response with no new revision; changed body → 409 IDEMPOTENCY_CONFLICT'
    check('09 幂等重放与内容冲突', idempotency)

    def cancel_history():
        before = employee.day(date)
        entry_id = int(before['entries'][0]['id'])
        onsite_id = int(before['onsite']['id'])
        history = sql(f'SELECT id,action,state,minutes,content FROM time_entry_revision WHERE entry_id={entry_id} ORDER BY revision_no')
        result = employee.save(date, {'expectedVersion': before['version'], 'entries': [], 'onsite': None})
        after = sql(f'SELECT id,action,state,minutes,content FROM time_entry_revision WHERE entry_id={entry_id} ORDER BY revision_no')
        onsite = sql(f'SELECT action,state FROM onsite_day_revision WHERE onsite_id={onsite_id} ORDER BY revision_no')
        assert result['entries'] == [] and result['onsite'] is None
        assert after[:-1] == history and after[-1][1:3] == ['CANCEL', 'CANCELED']
        assert onsite[0] == ['REPORT', 'DRAFT'] and onsite[-1] == ['CANCEL', 'CANCELED']
        return f'Entry {entry_id} and onsite {onsite_id} retain original history; cancellation adds a version'
    check('10 取消版本与历史保留', cancel_history)

    def old_month():
        current_month = NOW.date().replace(day=1)
        previous_month = (current_month - dt.timedelta(days=1)).replace(day=1)
        closed_month = (previous_month - dt.timedelta(days=1)).replace(day=1)
        old_date = closed_month.isoformat()
        before = employee.day(old_date)
        counts = database_snapshot(old_date)
        existing_period = sql(f"SELECT status FROM accounting_period WHERE month_start='{old_date}'")
        assert not existing_period or existing_period[0][0] in ('OPEN', 'CLOSED'), existing_period
        value = {'expectedVersion': before['version'], 'entries': [entry('4', '逾期门禁')], 'onsite': None}
        body = employee.save(old_date, value, expected=409)
        expected_code = 'PERIOD_CLOSED' if existing_period == [['CLOSED']] else 'PERIOD_CLOSING'
        assert body['code'] == expected_code, body
        assert employee.day(old_date) == before and database_snapshot(old_date) == counts
        return f'{old_date}: ordinary old-month write rejected without changing records → 409 {expected_code}'
    check('11 封账时点门禁', old_month)

    def leave_and_week():
        first, again = employee.day('2026-09-04'), employee.day('2026-09-04')
        assert first['baseMinutes'] == 480 and first['leaveMinutes'] == 240 and first['requiredMinutes'] == 240
        assert first == again
        monday = (NOW.date() - dt.timedelta(days=NOW.weekday())).isoformat()
        week = employee.request('GET', '/api/v1/weeks/' + monday)
        assert week['weekStart'] == monday and len(week['days']) == 7
        no_money(week)
        non_monday = (dt.date.fromisoformat(monday) + dt.timedelta(days=1)).isoformat()
        error = employee.request('GET', '/api/v1/weeks/' + non_monday, expected=400)
        assert error['code'] == 'INVALID_WEEK', error
        return '2026-09-04: 480−240=240 required minutes; repeat read stable; natural week has seven days; non-Monday rejected'
    check('12 半天假与自然周', leave_and_week)

    def employee_no_change():
        users = admin.request('GET', '/api/v1/master/users')
        original = next(user for user in users if user['id'] == '1')
        assert original['employeeNo'] == '98123'
        used = {user['employeeNo'] for user in users}
        replacement = next(f'VT{n:05d}' for n in range(100000) if f'VT{n:05d}' not in used)
        fields = ['employeeNo', 'wecomUserid', 'name', 'departmentId', 'level', 'status', 'effectiveFrom', 'roles']
        restore = {field: original[field] for field in fields}
        updated = dict(restore, employeeNo=replacement)
        prior_day = employee.day(date)
        changed = False
        try:
            new_user = admin.request('PUT', '/api/v1/master/users/1', updated)
            changed = True
            assert new_user['id'] == '1' and new_user['employeeNo'] == replacement
            replacement_client = Client()
            assert replacement_client.login(replacement)['id'] == '1'
            assert replacement_client.day(date) == prior_day
            assert employee.request('GET', '/api/v1/me')['employeeNo'] == replacement
            old_client = Client()
            old_client.csrf = old_client.request('GET', '/api/v1/auth/csrf')
            error = old_client.request('POST', '/api/v1/auth/local-login', {'employeeNo': '98123'}, expected=401)
            assert error['code'] == 'UNKNOWN_LOCAL_USER', error
        finally:
            if changed:
                restored = admin.request('PUT', '/api/v1/master/users/1', restore)
                assert restored['employeeNo'] == '98123' and restored['id'] == '1', 'Original employee number was not restored'
        assert Client().login('98123')['id'] == '1'
        return f'98123 → {replacement} → 98123, same internal ID 1 / existing session / historical day; old number rejected while changed'
    check('13 工号变更保持内部关联', employee_no_change)


def write_report(error):
    evidence = {'runId': RUN, 'executedAt': NOW.isoformat(), 'baseUrl': BASE, 'testDates': DATES,
                'results': RESULTS, 'overall': 'FAIL' if error else 'PASS'}
    output = ROOT / '.local' / ('api-verification-' + RUN + '.json')
    output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')
    lines = ['# A 阶段本地 API 验收记录', '', f'- 执行时间：{NOW.isoformat()}', f'- 运行编号：`{RUN}`',
             f'- 目标：`{BASE}`，仅 local profile + 固定测试身份 + 本地 MySQL/Redis。',
             f'- 结果：**{evidence["overall"]}**；{sum(r["status"] == "PASS" for r in RESULTS)} 项通过，{sum(r["status"] == "FAIL" for r in RESULTS)} 项失败。',
             '- 执行方式：项目根目录运行 `python3 scripts/verify-local-api.py`；先启动本地 API 和基础设施。',
             '- 数据策略：只选择未来 90 天内尚无记录的日期；写入“本地API验证”标记并保留版本/审计；不删除数据库卷。工号变更在 finally 恢复。',
             f'- 本次测试日期：{", ".join(DATES) if DATES else "尚未选择"}。', '',
             '| 检查 | 结果 | 实际证据 |', '|---|---|---|']
    for row in RESULTS:
        text = str(row['evidence']).replace('|', '\\|').replace('\n', ' ')
        lines.append(f'| {row["name"]} | {row["status"]} | {text} |')
    lines += ['', '完整原始结果保存在 `.local/api-verification-' + RUN + '.json`，不包含会话 Cookie、CSRF 值或生产密钥。', '',
              '本记录只验证已实现的本地 A 阶段 API。真实企微 SSO / OA、送审审批、正式成本报表、月报发布与完整 MVP 验收尚未由本脚本验证。基础设施与镜像检查见本地运行说明；这些结果不代表生产上线通过。', '']
    if error:
        lines += ['执行在首个失败处停止，后续用例未运行。修复后重跑会选择新的测试日期并更新本记录。', '']
    (ROOT / 'docs/development/local-api-verification.md').write_text('\n'.join(lines))


if __name__ == '__main__':
    failure = None
    try:
        main()
    except Exception as error:
        failure = error
    finally:
        write_report(failure)
    if failure:
        print('Verification stopped:', failure, file=sys.stderr)
        sys.exit(1)

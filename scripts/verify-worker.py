#!/usr/bin/env python3
"""Exercise a running LOCAL worker with HTTP-created fixtures and narrowly scoped job injections."""
import datetime as dt
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import time
import uuid
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('local_api_verification', ROOT / 'scripts/verify-local-api.py')
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)
Client, read_sql = helper.Client, helper.sql
RUN = dt.datetime.now(ZoneInfo('Asia/Shanghai')).strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:6]
MARKER = '本地worker验证 ' + RUN
RESULT = {'run': RUN, 'marker': MARKER, 'checks': []}

def note(name, evidence):
    RESULT['checks'].append({'name': name, 'result': 'PASS', 'evidence': evidence})
    print('PASS', name, json.dumps(evidence, ensure_ascii=False), flush=True)

def local_sql_write(query):
    # This helper is deliberately not a general remote SQL client: fixed local compose and explicit seed/mode guard in main.
    command = ['docker', 'compose', '-p', 'allen-worklog', '-f', str(ROOT / 'deploy/compose.local.yml'), 'exec', '-T', 'mysql', 'sh', '-c',
               'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --protocol=TCP --default-character-set=utf8mb4 -h 127.0.0.1 -u "$MYSQL_USER" "$MYSQL_DATABASE" -N -B']
    value = subprocess.run(command, input=query + '\n', text=True, capture_output=True, check=True, timeout=20).stdout.strip()
    return [line.split('\t') for line in value.splitlines()]

def enqueue(user, week, suffix, expired=False):
    assert re.fullmatch('[1-9][0-9]*', str(user))
    dt.date.fromisoformat(week)
    key = 'LOCAL-WORKER-' + RUN + '-' + suffix
    assert re.fullmatch('[A-Za-z0-9-]+', key)
    status = 'RUNNING' if expired else 'PENDING'
    lease = "DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE)" if expired else 'NULL'
    token = "'" + str(uuid.uuid4()) + "'" if expired else 'NULL'
    result = local_sql_write(f"""
      INSERT INTO job(type,business_key,payload,actor_id,status,planned_at,next_run_at,attempts,lease_token,lease_until,claimed_at)
      VALUES('AUTO_SUBMIT','{key}',JSON_OBJECT('userId','{user}','weekStart','{week}'),NULL,'{status}',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),{1 if expired else 0},{token},{lease},{'DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 2 MINUTE)' if expired else 'NULL'});
      SELECT LAST_INSERT_ID();
    """)
    return int(result[-1][0])

def wait_job(job_id):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        rows = read_sql(f"SELECT status,attempts,COALESCE(lease_token,''),COALESCE(error_message,''),COALESCE(CAST(result_json AS CHAR),'null') FROM job WHERE id={job_id}")
        row = rows[0]
        if row[0] == 'COMPLETED':
            return {'id': str(job_id), 'state': row[0], 'attempts': int(row[1]), 'leaseToken': row[2], 'result': json.loads(row[4])}
        if row[0] in ('FAILED', 'RETRY'):
            raise AssertionError(f'Worker job {job_id} did not complete: {row}')
        time.sleep(0.5)
    raise AssertionError(f'Worker job {job_id} did not complete within 45 seconds')

def counts(user):
    row = read_sql(f"""SELECT
      (SELECT COUNT(*) FROM approval_item i JOIN approval_package p ON p.id=i.package_id WHERE p.user_id={user}),
      (SELECT COUNT(*) FROM approval_package WHERE user_id={user}),
      (SELECT COUNT(*) FROM time_entry_revision r JOIN time_entry e ON e.id=r.entry_id JOIN day_record d ON d.id=e.day_id WHERE d.user_id={user}),
      (SELECT COUNT(*) FROM onsite_day_revision r JOIN onsite_day o ON o.id=r.onsite_id JOIN day_record d ON d.id=o.day_id WHERE d.user_id={user}),
      (SELECT COALESCE(SUM(r.cost_amount),0) FROM time_entry_revision r JOIN time_entry e ON e.id=r.entry_id JOIN day_record d ON d.id=e.day_id WHERE d.user_id={user}),
      (SELECT COALESCE(SUM(r.cost_amount),0) FROM onsite_day_revision r JOIN onsite_day o ON o.id=r.onsite_id JOIN day_record d ON d.id=o.day_id WHERE d.user_id={user})""")[0]
    return {'approvalItems': int(row[0]), 'approvalPackages': int(row[1]), 'timeRevisions': int(row[2]), 'onsiteRevisions': int(row[3]), 'laborCost': row[4], 'onsiteCost': row[5]}

def main():
    anonymous, admin, employee = Client(), Client(), Client()
    assert anonymous.request('GET', '/actuator/health')['status'] == 'UP'
    mode = anonymous.request('GET', '/api/v1/auth/status')
    assert mode['mode'] == 'local' and mode['localLoginEnabled'] is True, mode
    assert read_sql("SELECT employee_no,wecom_userid FROM app_user WHERE id=4") == [['00123', 'local-admin']]
    process_lines = subprocess.check_output(['ps', '-axo', 'pid=,command='], text=True).splitlines()
    workers = [line.strip() for line in process_lines if 'java -jar ' in line and '--spring.profiles.active=local,worker' in line]
    apis = [line.strip() for line in process_lines if 'java -jar ' in line and '--spring.profiles.active=local,api' in line]
    assert len(workers) == 1 and len(apis) == 1, {'workers': workers, 'apis': apis}
    RESULT['workerProcess'] = workers[0]
    RESULT['apiProcess'] = apis[0]
    note('01 本地环境与实际worker进程', {'worker': workers[0], 'api': apis[0], 'mode': mode['mode']})
    assert admin.login('00123')['id'] == '4'
    today = dt.datetime.now(ZoneInfo('Asia/Shanghai')).date()
    week = today - dt.timedelta(days=today.weekday() + 7)
    month = week.strftime('%Y-%m')
    assert admin.request('GET', '/api/v1/periods/' + month)['status'] == 'OPEN', 'Previous week month must still be open; do not unlock a real closed month for this script.'
    users = admin.request('GET', '/api/v1/master/users')
    existing = {u['employeeNo'] for u in users}
    employee_no = 'OP' + str(uuid.uuid4().int % 100000).zfill(5)
    while employee_no in existing:
        employee_no = 'OP' + str(uuid.uuid4().int % 100000).zfill(5)
    user = admin.request('POST', '/api/v1/master/users', {'employeeNo': employee_no, 'wecomUserid': 'local-worker-' + RUN, 'name': MARKER, 'departmentId': '1', 'level': 'MIDDLE', 'status': 'ACTIVE', 'effectiveFrom': week.isoformat(), 'roles': ['EMPLOYEE']})
    user_id = int(user['id'])
    project = admin.request('POST', '/api/v1/master/work-items', {'code': 'OP-' + RUN, 'name': MARKER + ' 项目', 'type': 'PROJECT', 'ownerDepartmentId': '1', 'approverUserId': '2', 'source': 'LOCAL', 'status': 'ACTIVE', 'effectiveFrom': week.isoformat()})
    RESULT.update({'user': user, 'project': project, 'weekStart': week.isoformat()})
    assert employee.login(employee_no)['id'] == str(user_id)
    days = []
    for offset in range(5):
        date = (week + dt.timedelta(days=offset)).isoformat()
        day = employee.day(date)
        if day['requiredMinutes'] == 480 and day['editable'] and not day['entries'] and day['onsite'] is None:
            days.append((date, day))
        if len(days) == 2:
            break
    assert len(days) == 2, 'Need two untouched 8h-base weekdays for isolated employee'
    valid_date, valid = days[0]
    invalid_date, invalid = days[1]
    def entry(hours, label):
        return {'id': None, 'workItemId': project['id'], 'kind': 'WORK', 'hours': hours, 'content': MARKER + ' ' + label, 'redReason': ''}
    valid = employee.save(valid_date, {'expectedVersion': valid['version'], 'entries': [entry('8', '完整日')], 'onsite': None})
    invalid = employee.save(invalid_date, {'expectedVersion': invalid['version'], 'entries': [entry('1', '不足日保留草稿')], 'onsite': {'id': None, 'workItemId': project['id'], 'reason': MARKER + ' 独立现场'}})
    assert valid['validation']['ready'] is True and invalid['validation']['ready'] is False
    RESULT['validDate'], RESULT['incompleteDate'] = valid_date, invalid_date
    note('02 HTTP创建有效/不足/独立现场', {'employeeNo': employee_no, 'userId': str(user_id), 'projectId': project['id'], 'effectiveFrom': week.isoformat(), 'validDate': valid_date, 'incompleteDate': invalid_date})
    first = wait_job(enqueue(user_id, week.isoformat(), 'first'))
    RESULT['firstJob'] = first
    after_valid, after_invalid = employee.day(valid_date), employee.day(invalid_date)
    assert after_valid['entries'][0]['state'] == 'PENDING', after_valid
    assert after_invalid['entries'][0]['state'] == 'DRAFT', after_invalid
    assert after_invalid['onsite']['state'] == 'PENDING', after_invalid
    assert any(item['code'] == 'DAY_INCOMPLETE' and item['date'] == invalid_date for item in first['result']['failed']), first
    baseline = counts(user_id)
    assert baseline['approvalItems'] == 2 and baseline['approvalPackages'] == 1, baseline
    assert float(baseline['laborCost']) == 0 and float(baseline['onsiteCost']) == 0, baseline
    RESULT['baseline'] = baseline
    note('03 运行worker真实自动提交', {'job': first, 'counts': baseline, 'states': ['PENDING', 'DRAFT', 'PENDING']})
    second = wait_job(enqueue(user_id, week.isoformat(), 'duplicate-payload'))
    assert counts(user_id) == baseline
    RESULT['duplicateJob'] = second
    note('04 同payload重复任务无新增业务/费用', {'jobId': second['id'], 'counts': counts(user_id)})
    recovered = wait_job(enqueue(user_id, week.isoformat(), 'expired-lease', expired=True))
    assert recovered['attempts'] == 2 and recovered['leaseToken'] == '', recovered
    assert counts(user_id) == baseline
    RESULT['recoveredJob'] = recovered
    note('05 过期RUNNING租约自动接管', {'job': recovered, 'counts': counts(user_id)})
    audit = read_sql(f"SELECT id,actor_type,COALESCE(CAST(actor_id AS CHAR),''),target_user_id,job_id,action,object_type,object_id FROM audit_event WHERE target_user_id={user_id} AND action='REVISION_SUBMITTED' ORDER BY id")
    assert len(audit) == 2 and all(a[1] == 'SYSTEM' and a[2] == '' and a[3] == str(user_id) and a[4].isdigit() for a in audit), audit
    RESULT['systemAudit'] = audit
    note('06 SYSTEM审计与目标/任务链路', audit)
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        notifications = employee.request('GET', '/api/v1/operations/my-notifications')
        failed = [n for n in notifications if n['eventType'] == 'AUTO_SUBMIT_FAILED' and invalid_date in n['body'] and n['status'] == 'LOCAL']
        if len(failed) >= 3:
            break
        time.sleep(0.5)
    assert len(failed) >= 3, notifications
    RESULT['failureNotifications'] = failed
    note('07 失败项送入本人LOCAL收件箱', {'count': len(failed), 'ids': [n['id'] for n in failed], 'notExternalWecom': True})
    RESULT['result'] = 'PASS'
    RESULT['finishedAt'] = dt.datetime.now(ZoneInfo('Asia/Shanghai')).isoformat()
    file = ROOT / '.local' / ('worker-verification-' + RUN + '.json')
    file.write_text(json.dumps(RESULT, ensure_ascii=False, indent=2) + '\n')
    print('RESULT_FILE', file, flush=True)

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        RESULT['result'] = 'FAIL'
        RESULT['error'] = str(error)
        file = ROOT / '.local' / ('worker-verification-' + RUN + '.json')
        file.write_text(json.dumps(RESULT, ensure_ascii=False, indent=2) + '\n')
        raise

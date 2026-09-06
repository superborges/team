#!/usr/bin/env python3
"""Verify that calendar changes recalculate leave from its source, using an isolated local identity.

Requires the local Docker Compose database and running local API. No seed day entries are edited.
The script preserves audit evidence, restores the calendar, revokes its test source, and disables its test user.
"""
import datetime as dt
import importlib.util
from pathlib import Path
import secrets
import subprocess

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("master_verify", ROOT / "scripts/verify-master-data.py")
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)


def sql(statement):
    result = subprocess.run([
        "docker", "compose", "-f", str(ROOT / "deploy/compose.local.yml"), "exec", "-T", "mysql", "sh", "-c",
        'MYSQL_PWD="$MYSQL_PASSWORD" mysql --protocol=TCP -h 127.0.0.1 -u "$MYSQL_USER" "$MYSQL_DATABASE" --batch --skip-column-names -e "$1"',
        "verify-calendar-leave", statement,
    ], capture_output=True, text=True, check=True)
    return result.stdout.strip()


def main():
    admin = api.Session()
    assert admin.call("GET", "/auth/status")["localLoginEnabled"], "This verification is only for the explicit local environment"
    admin.login("00123")
    existing = {u["employeeNo"] for u in admin.call("GET", "/master/users")}
    while True:
        employee = "CL" + str(secrets.randbelow(100000)).zfill(5)
        if employee not in existing:
            break
    user = admin.call("POST", "/master/users", {
        "employeeNo": employee, "wecomUserid": "local-calendar-verify-" + secrets.token_hex(8),
        "name": "本地日历请假验收人员", "departmentId": "1", "level": "MIDDLE", "status": "ACTIVE",
        "effectiveFrom": api.TODAY.isoformat(), "roles": ["EMPLOYEE"],
    })
    user_id = int(user["id"])
    person = api.Session()
    person.login(employee)
    next_month = (api.TODAY.replace(day=28) + dt.timedelta(days=4)).replace(day=1)
    day = (next_month + dt.timedelta(days=21)).isoformat()
    original_calendar = admin.call("GET", f"/master/calendar?from={day}&to={day}")[0]
    source_key = "LOCAL-CALENDAR-VERIFY-" + secrets.token_hex(12)
    source_created = False
    print("Isolated calendar/leave verification", user_id, day)
    try:
        # Only this test source is inserted directly; real ingestion remains in its own service and tests.
        sql(f"INSERT INTO leave_record(user_id,work_date,source_key,source_version,leave_minutes,start_minute,end_minute,status) VALUES ({user_id},'{day}','{source_key}','1',240,540,780,'APPROVED')")
        source_created = True
        admin.call("PUT", "/master/calendar/" + day, {"isWorkday": False, "baseMinutes": 0, "note": "本地验收：暂设休息日，检验请假重算"})
        before = person.call("GET", "/days/" + day)
        saved = person.call("PUT", "/days/" + day, {
            "expectedVersion": before["version"], "entries": [{"id": None, "workItemId": "1", "kind": "WORK", "hours": "4", "content": "完成日历与请假来源重新计算的本地验证", "redReason": ""}], "onsite": None,
        })
        assert saved["baseMinutes"] == 0 and saved["leaveMinutes"] == 0 and saved["requiredMinutes"] == 0
        assert sql(f"SELECT CONCAT(base_minutes,',',leave_minutes,',',required_minutes) FROM day_record WHERE user_id={user_id} AND work_date='{day}'") == "0,0,0"
        print("PASS rest-day save persists the clamped 0/0/0 base, leave and required values")

        admin.call("PUT", "/master/calendar/" + day, {"isWorkday": True, "baseMinutes": 480, "note": "本地验收：恢复工作日，从原始请假重新计算"})
        changed = person.call("GET", "/days/" + day)
        assert changed["baseMinutes"] == 480 and changed["leaveMinutes"] == 240 and changed["requiredMinutes"] == 240
        assert changed["version"] > saved["version"]
        assert sql(f"SELECT CONCAT(base_minutes,',',leave_minutes,',',required_minutes) FROM day_record WHERE user_id={user_id} AND work_date='{day}'") == "480,240,240"
        assert sql(f"SELECT leave_minutes FROM leave_record WHERE source_key='{source_key}'") == "240"
        print("PASS work-day change persists 480/240/240 from the original source and advances the day version")
        print("PASS original 240-minute leave source remains unchanged")
    finally:
        try:
            if source_created:
                # Revoke only this generated source, retaining the local test evidence.
                sql(f"UPDATE leave_record SET status='REVOKED',source_version='2' WHERE source_key='{source_key}'")
            admin.call("PUT", "/master/calendar/" + day, {key: original_calendar[key] for key in ("isWorkday", "baseMinutes", "note")})
        finally:
            current = next(u for u in admin.call("GET", "/master/users") if u["id"] == str(user_id))
            admin.call("PUT", "/master/users/" + str(user_id), api.user_input(current, status="INACTIVE", roles=["EMPLOYEE"]))


if __name__ == "__main__":
    main()

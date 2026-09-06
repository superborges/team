#!/usr/bin/env python3
"""Verify the running local API with isolated test identities (Python standard library).

Run: python3 scripts/verify-master-data.py [http://127.0.0.1:8080]
Creates explicitly named local verification records; does not edit seed identities.
The calendar is restored in finally; test identities are disabled after validation.
"""
import datetime as dt
import http.cookiejar
import json
import secrets
import sys
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080").rstrip("/") + "/api/v1"
TODAY = dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).date()


class Session:
    def __init__(self):
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.token = ""

    def call(self, method, path, body=None, expected=200):
        headers = {"Content-Type": "application/json"}
        if method != "GET":
            headers["X-CSRF-TOKEN"] = self.token
        request = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
        try:
            response = self.opener.open(request, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        raw = response.read().decode()
        data = json.loads(raw) if raw else None
        assert response.status == expected, f"{method} {path}: expected {expected}, got {response.status}: {data}"
        return data

    def login(self, employee_no):
        self.token = self.call("GET", "/auth/csrf")["token"]
        me = self.call("POST", "/auth/local-login", {"employeeNo": employee_no})
        self.token = self.call("GET", "/auth/csrf")["token"]
        return me


def user_input(view, **changes):
    body = {key: view[key] for key in ("employeeNo", "wecomUserid", "name", "departmentId", "level", "status", "effectiveFrom", "roles")}
    body.update(changes)
    return body


def check(condition, message):
    assert condition, message
    print("PASS", message)


def main():
    admin = Session()
    check(admin.call("GET", "/auth/status")["localLoginEnabled"], "explicit local login is enabled")
    admin.login("00123")
    existing = {row["employeeNo"] for row in admin.call("GET", "/master/users")}
    while True:
        employee_no = "7" + str(secrets.randbelow(10000)).zfill(4)
        if employee_no not in existing:
            break
    user = admin.call("POST", "/master/users", {
        "employeeNo": employee_no, "wecomUserid": "local-master-verify-" + secrets.token_hex(8),
        "name": "本地主数据验收人员", "departmentId": "1", "level": "MIDDLE", "status": "ACTIVE",
        "effectiveFrom": TODAY.isoformat(), "roles": ["EMPLOYEE"],
    })
    person = Session()
    original_id = user["id"]
    print("Created isolated local verification user", original_id, employee_no)
    calendar_before = None
    calendar_date = None
    try:
        check(person.login(employee_no)["id"] == original_id and isinstance(original_id, str), "created identity uses a stable string ID")
        person.call("GET", "/master/rates", expected=403)
        check(True, "ordinary employee cannot read confidential standards")

        user = admin.call("PUT", "/master/users/" + original_id, user_input(user, roles=["EMPLOYEE", "ADMIN"]))
        person.call("GET", "/master/rates")
        check(person.call("GET", "/me")["canManage"], "ADMIN granted on the creation day is immediately effective")
        user = admin.call("PUT", "/master/users/" + original_id, user_input(user, roles=["EMPLOYEE"]))
        person.call("GET", "/master/rates", expected=403)
        check(not person.call("GET", "/me")["canManage"], "same-day ADMIN revocation affects the existing session")

        user = admin.call("PUT", "/master/users/" + original_id, user_input(user, status="INACTIVE"))
        person.call("GET", "/me", expected=401)
        check(True, "same-day account deactivation invalidates an existing session")
        user = admin.call("PUT", "/master/users/" + original_id, user_input(user, status="ACTIVE"))
        check(person.login(employee_no)["id"] == original_id, "same-day account reactivation preserves its ID")

        new_employee_no = "QA" + employee_no
        user = admin.call("PUT", "/master/users/" + original_id, user_input(user, employeeNo=new_employee_no))
        check(person.call("GET", "/me")["employeeNo"] == new_employee_no and user["id"] == original_id, "employee number changes preserve the same identity and active session")
        user = admin.call("PUT", "/master/users/" + original_id, user_input(user, employeeNo=employee_no))

        next_month = (TODAY.replace(day=28) + dt.timedelta(days=4)).replace(day=1)
        calendar_date = next_month + dt.timedelta(days=20)
        date_text = calendar_date.isoformat()
        calendar_before = admin.call("GET", f"/master/calendar?from={date_text}&to={date_text}")[0]
        draft = person.call("GET", "/days/" + date_text)
        payload = {"expectedVersion": draft["version"], "entries": [{"id": None, "workItemId": "1", "kind": "WORK", "hours": "4", "content": "完成本地基础资料联调与验收记录", "redReason": ""}], "onsite": None}
        saved = person.call("PUT", "/days/" + date_text, payload)
        new_base = 480 if calendar_before["baseMinutes"] == 0 else 0
        admin.call("PUT", "/master/calendar/" + date_text, {"isWorkday": new_base > 0, "baseMinutes": new_base, "note": "本地隔离验收：工作日历修改"})
        changed = person.call("GET", "/days/" + date_text)
        check(changed["version"] > saved["version"] and changed["baseMinutes"] == new_base, "calendar changes advance the existing day version and replace its base")
        payload["expectedVersion"] = saved["version"]
        payload["entries"][0]["id"] = saved["entries"][0]["id"]
        person.call("PUT", "/days/" + date_text, payload, expected=409)
        check(True, "stale drafts cannot overwrite a changed calendar base")

        rates = admin.call("GET", "/master/rates")
        latest = max(dt.date.fromisoformat(rate["effectiveFrom"]) for rate in rates if rate["dimension"] == "ONSITE")
        boundary = max(dt.date(TODAY.year + 10, 1, 1), latest + dt.timedelta(days=1))
        rate_input = {"dimension": "ONSITE", "dailyRate": "123.4567", "effectiveFrom": boundary.isoformat(), "effectiveTo": None}
        added = admin.call("POST", "/master/rates", rate_input)
        after = [rate for rate in admin.call("GET", "/master/rates") if rate["dimension"] == "ONSITE"]
        check(any(rate["effectiveTo"] == boundary.isoformat() for rate in after if rate["id"] != added["id"]), "a new rate closes the previous open interval at its exclusive boundary")
        admin.call("POST", "/master/rates", rate_input, expected=422)
        admin.call("POST", "/master/rates", {**rate_input, "effectiveFrom": "2020-01-01"}, expected=409)
        check(True, "duplicate rate boundaries and already-cut-off history are rejected")
        check(any(rate["dailyRate"] == "123.4567" for rate in after if rate["id"] == added["id"]), "rate precision is preserved as a decimal string")
        print(json.dumps({"result": "passed", "testUserId": original_id, "employeeNo": employee_no, "futureRateBoundary": boundary.isoformat()}, ensure_ascii=False))
    finally:
        try:
            if calendar_before is not None:
                admin.call("PUT", "/master/calendar/" + calendar_date.isoformat(), {key: calendar_before[key] for key in ("isWorkday", "baseMinutes", "note")})
        finally:
            # Preserve evidence, but stop the isolated identity even when calendar restoration fails.
            rows = admin.call("GET", "/master/users")
            current = next(row for row in rows if row["id"] == original_id)
            admin.call("PUT", "/master/users/" + original_id, user_input(current, status="INACTIVE", roles=["EMPLOYEE"]))


if __name__ == "__main__":
    main()

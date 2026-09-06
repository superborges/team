package com.allen.worklog.master;

import com.allen.worklog.common.ApiException;
import java.time.LocalDate;
import java.util.Set;

public final class MasterValidation {
    private MasterValidation() {}

    public static String employeeNo(String value) {
        if (value == null || !value.matches("(?:[0-9]{5}|[A-Za-z]{2}[0-9]{5})")) {
            throw new ApiException(422, "INVALID_EMPLOYEE_NO", "工号须为五位数字或两位英文字母加五位数字，按原值保留大小写和前导零");
        }
        return value;
    }

    public static String text(String value, int max, String label) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new ApiException(422, "INVALID_FIELD", label + "不能为空且最多 " + max + " 个字符");
        }
        return value.strip();
    }

    public static String oneOf(String value, Set<String> allowed, String label) {
        if (!allowed.contains(value == null ? "" : value)) {
            throw new ApiException(422, "INVALID_FIELD", label + "不在允许范围内");
        }
        return value;
    }

    public static void interval(LocalDate from, LocalDate to) {
        if (from == null || (to != null && !to.isAfter(from))) {
            throw new ApiException(422, "INVALID_INTERVAL", "生效起日必填，止日必须晚于起日（止日不含）");
        }
    }

    public static long id(String value, String label) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
            // Untrusted identifiers are validated before reaching a bound SQL parameter.
        }
        throw new ApiException(422, "INVALID_ID", label + "须为有效 ID");
    }

    public static Long optionalId(String value, String label) {
        return value == null || value.isBlank() ? null : id(value, label);
    }
}

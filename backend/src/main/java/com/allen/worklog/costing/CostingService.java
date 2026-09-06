package com.allen.worklog.costing;

import com.allen.worklog.common.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

@Service
public class CostingService {
    public static final String POLICY_VERSION="STANDARD_DAILY_480_HALF_UP_V1";
    private final JdbcClient jdbc;
    public CostingService(JdbcClient jdbc) { this.jdbc=jdbc; }

    /** The caller invokes this inside the approval transaction and persists this returned snapshot. */
    public LaborCost resolveLabor(long userId,LocalDate workDate,int minutes) {
        if (workDate==null || minutes<=0 || minutes>1440 || minutes%30!=0)
            throw new ApiException(422,"INVALID_COST_INPUT","工时计价日期及 30 分钟步长须合法");
        var rows=jdbc.sql("""
            SELECT r.id rate_id,h.id level_history_id,h.level_code,r.daily_rate
            FROM user_level_history h JOIN rate_card r ON r.level_code=h.level_code
            WHERE h.user_id=? AND h.valid_from<=? AND (h.valid_to IS NULL OR h.valid_to>?)
              AND r.valid_from<=? AND (r.valid_to IS NULL OR r.valid_to>?)
            """).params(userId,workDate,workDate,workDate,workDate)
            .query((rs,n) -> new LaborCost(rs.getLong("rate_id"),rs.getLong("level_history_id"),rs.getString("level_code"),
                rs.getBigDecimal("daily_rate"),minutes,laborAmount(rs.getBigDecimal("daily_rate"),minutes),POLICY_VERSION)).list();
        if (rows.size()!=1) throw new ApiException(422,"RATE_MISSING",rows.isEmpty()?"发生日缺少唯一职级及有效日标准，不能确认成本":"发生日存在交叠职级或标准，请先核实历史");
        return rows.getFirst();
    }

    public OnsiteCost resolveOnsite(LocalDate date) {
        if (date==null) throw new ApiException(422,"INVALID_COST_INPUT","现场计价日期必填");
        var rows=jdbc.sql("SELECT id,daily_rate FROM onsite_rate WHERE valid_from<=? AND (valid_to IS NULL OR valid_to>?)")
            .params(date,date).query((rs,n) -> new OnsiteCost(rs.getLong("id"),rs.getBigDecimal("daily_rate"),
                rs.getBigDecimal("daily_rate").setScale(2,RoundingMode.HALF_UP),POLICY_VERSION)).list();
        if (rows.size()!=1) throw new ApiException(422,"RATE_MISSING","发生日须有唯一有效现场日标准，不能以零金额替代缺失标准");
        return rows.getFirst();
    }

    public static BigDecimal laborAmount(BigDecimal dailyRate,int minutes) {
        if (dailyRate==null || dailyRate.signum()<0 || minutes<=0 || minutes>1440 || minutes%30!=0)
            throw new ApiException(422,"INVALID_COST_INPUT","日标准与计价分钟不合法");
        return dailyRate.multiply(BigDecimal.valueOf(minutes)).divide(BigDecimal.valueOf(480),2,RoundingMode.HALF_UP);
    }
    public record LaborCost(long rateId,long levelHistoryId,String levelCode,BigDecimal dailyRate,int minutes,BigDecimal amount,String policyVersion) {}
    public record OnsiteCost(long rateId,BigDecimal dailyRate,BigDecimal amount,String policyVersion) {}
}

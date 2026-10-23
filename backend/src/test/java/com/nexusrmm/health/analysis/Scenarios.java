package com.nexusrmm.health.analysis;

import com.nexusrmm.entity.MetricType;
import com.nexusrmm.health.support.FakeHealthData;

import static com.nexusrmm.health.analysis.TestSupport.FIVE_MIN;

/** Whole-machine fixtures shared by the engine, risk and facade tests. */
public final class Scenarios {

    private Scenarios() {
    }

    /** A desktop-like machine with a week of flat, unremarkable history and a quiet last hour. */
    public static FakeHealthData healthyWeek() {
        return new FakeHealthData()
                .history(MetricType.CPU_UTILIZATION, null, 7, FIVE_MIN, t -> 30).recentConstant(MetricType.CPU_UTILIZATION, null, 30)
                .history(MetricType.RAM_UTILIZATION, null, 7, FIVE_MIN, t -> 50).recentConstant(MetricType.RAM_UTILIZATION, null, 50)
                .history(MetricType.STORAGE_UTILIZATION, "C:\\", 7, FIVE_MIN, t -> 50).recentConstant(MetricType.STORAGE_UTILIZATION, "C:\\", 50)
                .history(MetricType.CPU_TEMPERATURE, null, 7, FIVE_MIN, t -> 55).recentConstant(MetricType.CPU_TEMPERATURE, null, 55);
    }

    /** Only the last hour: enough to score the live metrics, nowhere near enough for trends or baselines. */
    public static FakeHealthData justOneHour() {
        return new FakeHealthData()
                .recentConstant(MetricType.CPU_UTILIZATION, null, 30)
                .recentConstant(MetricType.RAM_UTILIZATION, null, 50)
                .recentConstant(MetricType.STORAGE_UTILIZATION, "C:\\", 50)
                .recentConstant(MetricType.CPU_TEMPERATURE, null, 55);
    }
}

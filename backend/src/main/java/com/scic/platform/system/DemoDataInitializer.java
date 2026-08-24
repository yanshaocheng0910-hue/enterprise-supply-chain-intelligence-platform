package com.scic.platform.system;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Component
public class DemoDataInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public DemoDataInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer count = jdbc.queryForObject("select count(*) from demand_history", Integer.class);
        if (count != null && count > 0) return;

        LocalDate start = LocalDate.of(2025, 8, 24);
        List<Object[]> rows = new ArrayList<>();
        for (long materialId = 1; materialId <= 5; materialId++) {
            double base = switch ((int) materialId) {
                case 1 -> 52;
                case 2 -> 38;
                case 3 -> 86;
                case 4 -> 23;
                default -> 44;
            };
            for (int day = 0; day < 365; day++) {
                double weekly = Math.sin((day % 7) * Math.PI / 3.5) * base * 0.16;
                double monthly = Math.sin(day * Math.PI / 15.0) * base * 0.08;
                double trend = materialId == 1 ? day * 0.025 : materialId == 3 ? day * 0.012 : 0;
                double campaign = day % 91 >= 78 && day % 91 <= 82 ? base * 0.45 : 0;
                double deterministicNoise = ((day * 37 + materialId * 19) % 17 - 8) * base * 0.009;
                double value = Math.max(0, base + weekly + monthly + trend + campaign + deterministicNoise);
                rows.add(new Object[]{materialId, start.plusDays(day), BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP), "DEMO_GENERATOR_V1", "DEMO_SYNTHETIC"});
            }
        }
        jdbc.batchUpdate("insert into demand_history(material_id, demand_date, quantity, source_system, data_label) values(?,?,?,?,?)", rows);
    }
}


package com.getjobs.application.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LagouServiceTest {
    @Test
    void salaryMedianIgnoresAnnualSalarySuffix() {
        assertEquals(25.0, LagouService.parseSalaryMedianK("20-30K·13薪"));
    }
}

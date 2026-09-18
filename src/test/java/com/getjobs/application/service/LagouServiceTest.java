package com.getjobs.application.service;

import com.getjobs.application.entity.LagouConfigEntity;
import com.getjobs.application.mapper.LagouConfigMapper;
import com.getjobs.application.mapper.LagouJobDataMapper;
import com.getjobs.application.mapper.LagouOptionMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LagouServiceTest {
    @Test
    void salaryMedianIgnoresAnnualSalarySuffix() {
        assertEquals(25.0, LagouService.parseSalaryMedianK("20-30K·13薪"));
    }

    @Test
    void persistsCustomCityInFirstConfig() {
        LagouConfigMapper configMapper = mock(LagouConfigMapper.class);
        javax.sql.DataSource dataSource = mock(javax.sql.DataSource.class);
        LagouService service = new LagouService(configMapper, mock(LagouOptionMapper.class), mock(LagouJobDataMapper.class), dataSource);
        LagouConfigEntity saved = new LagouConfigEntity();
        saved.setId(1L);
        when(configMapper.selectOne(any())).thenReturn(null).thenReturn(saved);

        LagouConfigEntity incoming = new LagouConfigEntity();
        incoming.setKeywords("[\"Java\"]");
        incoming.setCity("珠海");
        incoming.setResumeType("ONLINE");
        incoming.setMaxCount(50);
        service.saveOrUpdateFirstSelective(incoming);

        ArgumentCaptor<LagouConfigEntity> captor = ArgumentCaptor.forClass(LagouConfigEntity.class);
        verify(configMapper).insert(captor.capture());
        assertEquals("珠海", captor.getValue().getCity());
        assertEquals(50, captor.getValue().getMaxCount());
    }
}

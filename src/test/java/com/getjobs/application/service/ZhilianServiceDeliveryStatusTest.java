package com.getjobs.application.service;

import com.getjobs.application.mapper.ZhilianConfigMapper;
import com.getjobs.application.mapper.ZhilianJobDataMapper;
import com.getjobs.application.mapper.ZhilianOptionMapper;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ZhilianServiceDeliveryStatusTest {
    @Test
    void returnsAffectedRowsWhenMarkingDelivery() {
        ZhilianJobDataMapper mapper = mock(ZhilianJobDataMapper.class);
        when(mapper.update(any(), any())).thenReturn(1);
        ZhilianService service = new ZhilianService(
                mock(ZhilianConfigMapper.class),
                mock(ZhilianOptionMapper.class),
                mapper,
                mock(DataSource.class));

        assertEquals(1, service.markDeliveredByJobId("job-1"));
    }
}

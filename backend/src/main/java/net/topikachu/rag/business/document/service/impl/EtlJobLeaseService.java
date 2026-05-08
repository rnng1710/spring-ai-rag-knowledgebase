package net.topikachu.rag.business.document.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import net.topikachu.rag.business.document.entity.EtlJob;
import net.topikachu.rag.business.document.entity.EtlJobStatus;
import net.topikachu.rag.business.document.mapper.EtlJobMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class EtlJobLeaseService {

    private final EtlJobMapper etlJobMapper;

    public boolean renewLease(String jobId, String workerId, LocalDateTime lockedUntil) {
        int rows = etlJobMapper.update(null,
                Wrappers.<EtlJob>lambdaUpdate()
                        .set(EtlJob::getLockedUntil, lockedUntil)
                        .set(EtlJob::getUpdateDate, LocalDateTime.now())
                        .eq(EtlJob::getId, jobId)
                        .eq(EtlJob::getStatus, EtlJobStatus.RUNNING.name())
                        .eq(EtlJob::getLockedBy, workerId));
        return rows == 1;
    }
}

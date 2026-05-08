package net.topikachu.rag.business.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.topikachu.rag.business.document.entity.EtlJob;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EtlJobMapper extends BaseMapper<EtlJob> {
}

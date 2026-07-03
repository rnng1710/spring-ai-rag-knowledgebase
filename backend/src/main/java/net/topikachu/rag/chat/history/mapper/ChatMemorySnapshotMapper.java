package net.topikachu.rag.chat.history.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.topikachu.rag.chat.history.entity.ChatMemorySnapshotEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMemorySnapshotMapper extends BaseMapper<ChatMemorySnapshotEntity> {
}

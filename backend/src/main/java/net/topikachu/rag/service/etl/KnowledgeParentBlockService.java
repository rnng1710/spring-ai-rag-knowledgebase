package net.topikachu.rag.service.etl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.entity.KnowledgeParentBlock;
import net.topikachu.rag.business.document.mapper.KnowledgeParentBlockMapper;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class KnowledgeParentBlockService {

    // 每次父块-子块 schema 变更时递增此版本号，Milvus 检索时按此过滤，隔离不同格式的历史数据
    public static final int CHUNK_SCHEMA_VERSION = 2;

    private final KnowledgeParentBlockMapper parentBlockMapper;

    public Mono<Void> replaceForDocument(String docUuid, List<KnowledgeParentBlock> parentBlocks) {
        return Mono.fromRunnable(() -> replaceForDocumentSync(docUuid, parentBlocks))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    // 先删后插实现原子替换：当前 mapper 不支持 upsert，且 ETL 重跑时需清理旧父块
    public void replaceForDocumentSync(String docUuid, List<KnowledgeParentBlock> parentBlocks) {
        deleteByDocUuidSync(docUuid);
        if (parentBlocks == null || parentBlocks.isEmpty()) {
            return;
        }
        for (KnowledgeParentBlock parentBlock : parentBlocks) {
            parentBlockMapper.insert(parentBlock);
        }
    }

    public Mono<Void> deleteByDocUuid(String docUuid) {
        return Mono.fromRunnable(() -> deleteByDocUuidSync(docUuid))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    public void deleteByDocUuidSync(String docUuid) {
        parentBlockMapper.delete(Wrappers.<KnowledgeParentBlock>lambdaQuery()
                .eq(KnowledgeParentBlock::getDocUuid, docUuid));
    }

    public Mono<Map<String, KnowledgeParentBlock>> findByParentBlockIds(List<String> parentBlockIds) {
        return Mono.fromCallable(() -> findByParentBlockIdsSync(parentBlockIds))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Map<String, KnowledgeParentBlock> findByParentBlockIdsSync(List<String> parentBlockIds) {
        if (parentBlockIds == null || parentBlockIds.isEmpty()) {
            return Map.of();
        }
        List<KnowledgeParentBlock> blocks = parentBlockMapper.selectList(
                Wrappers.<KnowledgeParentBlock>lambdaQuery()
                        .in(KnowledgeParentBlock::getParentBlockId, parentBlockIds));
        Map<String, KnowledgeParentBlock> byId = new LinkedHashMap<>();
        for (KnowledgeParentBlock block : blocks) {
            byId.put(block.getParentBlockId(), block);
        }
        return byId;
    }

    public Mono<Void> refreshMetadata(Document doc) {
        return Mono.fromRunnable(() -> refreshMetadataSync(doc))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    public void refreshMetadataSync(Document doc) {
        if (doc == null || doc.getDocUuid() == null) {
            return;
        }
        parentBlockMapper.update(null,
                Wrappers.<KnowledgeParentBlock>lambdaUpdate()
                        .set(KnowledgeParentBlock::getFileName, doc.getFileName())
                        .set(KnowledgeParentBlock::getSpaceCode, doc.getSpaceCode())
                        .set(KnowledgeParentBlock::getTags, doc.getTags())
                        .set(KnowledgeParentBlock::getAclVersion, doc.getAclVersion())
                        .set(KnowledgeParentBlock::getUpdateDate, LocalDateTime.now())
                        .eq(KnowledgeParentBlock::getDocUuid, doc.getDocUuid()));
    }
}

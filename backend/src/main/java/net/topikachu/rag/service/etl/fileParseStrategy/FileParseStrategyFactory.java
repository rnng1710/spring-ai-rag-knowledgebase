package net.topikachu.rag.service.etl.fileParseStrategy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FileParseStrategyFactory {

    // Spring 自动收集所有 FileParseStrategy 实现类注入此列表，按文件扩展名遍历匹配
    @Autowired
    private List<FileParseStrategy> strategies;

    public FileParseStrategy getFileParseStrategy(String fileType) {
        FileParseStrategy result = getFileParseStrategyOrNull(fileType);
        if (result == null) {
            throw new IllegalStateException("Unsupported file type: " + fileType);
        }
        return result;
    }

    public FileParseStrategy getFileParseStrategyOrNull(String fileType) {
        for (FileParseStrategy strategy : strategies) {
            if (strategy.supports(fileType)) {
                return strategy;
            }
        }
        return null;
    }
}

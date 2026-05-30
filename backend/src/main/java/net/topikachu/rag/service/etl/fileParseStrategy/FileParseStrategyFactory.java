package net.topikachu.rag.service.etl.fileParseStrategy;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class FileParseStrategyFactory {

    private Map<String, FileParseStrategy> fileParseStrategyMap = new HashMap<>();

    @Autowired
    private List<FileParseStrategy> fileParseStrategyList;

    @PostConstruct
    public void init() {
        for(FileParseStrategy fileParseStrategy : fileParseStrategyList){
            fileParseStrategyMap.put(fileParseStrategy.getFileType(), fileParseStrategy);
        }
    }

    public FileParseStrategy getFileParseStrategy(String fileType){
        FileParseStrategy fileParseStrategy = fileParseStrategyMap.get(fileType);
        if(fileParseStrategy == null){
            throw new IllegalStateException("Unsupported file type: " + fileType);
        }
        return fileParseStrategy;
    }

    public FileParseStrategy getFileParseStrategyOrNull(String fileType) {
        return fileParseStrategyMap.get(fileType);
    }
}

package net.topikachu.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Data
@ConfigurationProperties(prefix = "rag.object-storage")
public class ObjectStorageProperties {

    /**
     * 是否启用对象存储
     */
    private  boolean enabled = true;

    /**
     * 对象存储服务地址，例如：
     * MinIo：http://localhost:9000
     * AWS S3: https://s3.us-east-1.amazonaws.com
     */
    private String endpoint;

    /**
     * 区域，AWS S3通常需要；MinIo可以给默认值us-east-1
     */
    private String region = "us-east-1";

    /**
     * 桶名，类似文件存储的顶层空间
     */
    private String bucket;

    /**
     * 访问账号
     */
    private String accessKey;

    /**
     * 访问密钥
     */
    private String secretKey;

    /**
     * 是否使用path0style访问
     * 本地MinIo通常建议true
     */
    private boolean pathStyleAccess = true;

    /**
     * 文件上传、解析、ETL前的本地临时目c
     */
    private String tempDirectory = System.getProperty("java.io.tmpdir") + "/rag-object-storage";
}

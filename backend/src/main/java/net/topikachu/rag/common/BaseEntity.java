package net.topikachu.rag.common;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.EqualsAndHashCode;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Date;

@Data
@EqualsAndHashCode(callSuper = false)
public class BaseEntity {

    @TableId("ID")
    private String id;

    /**
     * 创建用户id
     */
    @TableField("CREATE_USER_ID")
    private String createUserId;

    /**
     * 创建用户名
     */
    @TableField("CREATE_USER_NAME")
    private String createUserName;

    /**
     * 创建时间
     */
    @TableField("CREATE_DATE")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createDate;

    /**
     * 修改用户id
     */
    @TableField("UPDATE_USER_ID")
    private String updateUserId;

    /**
     * 修改用户名
     */
    @TableField("UPDATE_USER_NAME")
    private String updateUserName;

    /**
     * 修改时间
     */
    @TableField("UPDATE_DATE")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateDate;

    /**
     * 备注
     */
    @TableField("REMARK")
    private String remark;

    /**
     * 版本
     */
    @TableField("VERSION")
    @Version
    private Integer version;

    public boolean isNew() {
        return !StringUtils.hasText(getId());
    }
}

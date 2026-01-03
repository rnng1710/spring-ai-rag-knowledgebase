package net.topikachu.rag.common;


import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.http.HttpStatus;

@Setter
@Getter
@Accessors(chain = true)
public class AjaxResult {

    /**
     * 提示信息成功
     */
    public static final Integer SUCCESS_CODE = 0;

    /**
     * 提示信息错误
     */
    public static final Integer ERROR_CODE = -1;

    /**
     * 提示信息警告
     */
    public static final Integer WARN_CODE = 1;

    /**
     * 提示信息成功
     */
    public static final String PROMPT_SUCCESS = "success";

    /**
     * 提示信息警告
     */
    public static final String PROMPT_WARN = "warn";

    /**
     * 提示信息错误
     */
    public static final String PROMPT_ERROR = "error";

    /**
     * 状态码
     */
    private Integer code;

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 提示信息
     */
    private String msg;

    /**
     * 标签
     */
    private String tag = "";

    /**
     * 数据
     */
    private Object data;

    // public AjaxResult put(String key, Object value) {
    //     super.put(key, value);
    //     return this;
    // }

    public static AjaxResult error() {

        return error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "未知异常，请联系管理员");

    }

    public static AjaxResult error(String msg) {

        return error(HttpStatus.INTERNAL_SERVER_ERROR.value(), msg);
    }

    public static AjaxResult error(int code, String msg) {
        AjaxResult ajaxResult = new AjaxResult();
        ajaxResult.setCode(code);
        ajaxResult.setSuccess(false);
        ajaxResult.setMsg(msg);
        ajaxResult.setTag(AjaxResult.PROMPT_ERROR);
        return ajaxResult;
    }

    public static AjaxResult success() {
        AjaxResult ajaxResult = new AjaxResult();
        ajaxResult.setCode(SUCCESS_CODE);
        ajaxResult.setSuccess(true);
        ajaxResult.setMsg("");
        ajaxResult.setTag(AjaxResult.PROMPT_SUCCESS);
        return ajaxResult;
    }

    public static AjaxResult success(Object data) {
        AjaxResult ajaxResult = success();
        ajaxResult.setData(data);
        return ajaxResult;
    }

    public static AjaxResult success(String msg, Object data) {
        AjaxResult ajaxResult = new AjaxResult();
        ajaxResult.setCode(SUCCESS_CODE);
        ajaxResult.setSuccess(true);
        ajaxResult.setMsg(msg);
        ajaxResult.setData(data);
        return ajaxResult;
    }

    public static AjaxResult warn(int code, String msg, Object data) {
        AjaxResult ajaxResult = new AjaxResult();
        ajaxResult.setCode(code);
        ajaxResult.setSuccess(false);
        ajaxResult.setMsg(msg);
        ajaxResult.setTag(AjaxResult.PROMPT_WARN);
        ajaxResult.setData(data);
        return ajaxResult;
    }

    public static AjaxResult warn(String msg) {
        AjaxResult ajaxResult = new AjaxResult();
        ajaxResult.setCode(WARN_CODE);
        ajaxResult.setSuccess(false);
        ajaxResult.setMsg(msg);
        ajaxResult.setTag(AjaxResult.PROMPT_WARN);
        return ajaxResult;
    }

}

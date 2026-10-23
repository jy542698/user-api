package com.jiaoyu.user_api.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局异常处理器：把异常统一成 RealWorld 风格的 JSON：{"errors":{"body":["错误信息"]}}
 *
 * <p>兼容性铁律：{@code errors.body} 恒存在（旧前端与旧代码只读这个键）。
 * 校验错误在此基础上**额外**按真实字段名加一个键（title / body / email / password ...），
 * 供前端做字段级 inline 提示，属于纯增量，不破坏任何调用方。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException ex) {
        Map<String, Object> errors = Map.of("body", List.of(ex.getMessage()));
        Map<String, Object> body = Map.of("errors", errors);
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    /**
     * Bean Validation 失败（@Valid / @Validated 触发的请求体校验）。
     *
     * <p>返回 400，errors.body 一定有值，且额外带字段级键。</p>
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> errors = new LinkedHashMap<>();
        List<String> messages = new ArrayList<>();

        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            String message = resolveMessage(fieldError.getDefaultMessage());
            messages.add(message);
            // 嵌套路径 article.title -> 只保留末段 title，前端才能按字段名取到提示。
            // 值统一是**数组**：与 body 同构（{"errors":{"email":["…"],"body":["…"]}}），
            // 前端 pickFieldError 取 [0] 即可，不必为两种形态写两套分支
            errors.putIfAbsent(lastSegment(fieldError.getField()), List.of(message));
        }
        // 挂在类上的约束（@Valid 外层对象）没有字段名，统一进 body
        for (ObjectError globalError : ex.getBindingResult().getGlobalErrors()) {
            messages.add(resolveMessage(globalError.getDefaultMessage()));
        }

        // body 恒存在：拿不到任何消息时也给一句中文兜底
        errors.put("body", messages.isEmpty() ? List.of("参数不合法") : messages);
        return ResponseEntity.badRequest().body(Map.of("errors", errors));
    }

    /** 方法参数上的约束校验失败（@Validated 打在 @RequestParam 上时走这条）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, Object> errors = new LinkedHashMap<>();
        List<String> messages = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String message = resolveMessage(violation.getMessage());
            messages.add(message);
            String path = violation.getPropertyPath() == null ? "" : violation.getPropertyPath().toString();
            errors.putIfAbsent(lastSegment(path), List.of(message));
        }
        errors.put("body", messages.isEmpty() ? List.of("参数不合法") : messages);
        return ResponseEntity.badRequest().body(Map.of("errors", errors));
    }

    /**
     * 请求体无法解析（畸形 JSON / 空 body / 类型不匹配）。
     *
     * <p>不处理的话会冒到 GenericFilterBean 之外变成 500，前端只能拿到没有语义的错误。</p>
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleNotReadable(HttpMessageNotReadableException ex) {
        Map<String, Object> errors = Map.of("body", List.of("请求体格式不正确，请检查 JSON 语法"));
        return ResponseEntity.badRequest().body(Map.of("errors", errors));
    }

    /** 查询参数类型不匹配（如 ?limit=abc），给 400 + 中文提示，而不是让容器返回无 errors 体的 400。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String name = ex.getName() == null ? "参数" : ex.getName();
        Map<String, Object> errors = new LinkedHashMap<>();
        // 与其它字段级错误保持同构：值也是数组
        errors.put(name, List.of("参数 " + name + " 的格式不正确"));
        errors.put("body", List.of("参数 " + name + " 的格式不正确"));
        return ResponseEntity.badRequest().body(Map.of("errors", errors));
    }

    /**
     * 上传文件超过容器上限。
     *
     * <p>业务层自己也校验了大小，但容器在进入 Controller **之前**就会因为
     * {@code spring.servlet.multipart.max-file-size} 抛这个异常。不兜住的话
     * 前端只会收到一个没有 errors 体的 500。</p>
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        String msg = "图片超过大小上限，请压缩后重试";
        Map<String, Object> errors = new LinkedHashMap<>();
        errors.put("file", List.of(msg));
        errors.put("body", List.of(msg));
        return ResponseEntity.badRequest().body(Map.of("errors", errors));
    }

    /** multipart 请求本身不合法（缺少 boundary、分段损坏等）。 */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, Object>> handleMultipart(MultipartException ex) {
        String msg = "上传请求格式不正确，请重新选择文件";
        Map<String, Object> errors = new LinkedHashMap<>();
        errors.put("file", List.of(msg));
        errors.put("body", List.of(msg));
        return ResponseEntity.badRequest().body(Map.of("errors", errors));
    }

    private String resolveMessage(String raw) {
        return (raw == null || raw.isBlank()) ? "参数不合法" : raw;
    }

    private String lastSegment(String path) {
        if (path == null || path.isBlank()) {
            return "body";
        }
        int index = path.lastIndexOf('.');
        String segment = index >= 0 ? path.substring(index + 1) : path;
        return segment.isBlank() ? "body" : segment;
    }
}

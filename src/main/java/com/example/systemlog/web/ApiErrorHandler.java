package com.example.systemlog.web;

import jakarta.validation.ConstraintViolationException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.*;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.MissingServletRequestParameterException;

@RestControllerAdvice
public class ApiErrorHandler {
    private final Logger log=LoggerFactory.getLogger(getClass());
    private ResponseEntity<?> error(int status,String code,String message) {
        return ResponseEntity.status(status).body(Map.of("error",Map.of("code",code,"message",message)));
    }
    @ExceptionHandler(ApiException.class) public ResponseEntity<?> api(ApiException ex){return error(ex.status,"HTTP_"+ex.status,ex.getMessage());}
    @ExceptionHandler(DuplicateKeyException.class) public ResponseEntity<?> duplicate(Exception ex){return error(409,"DUPLICATE","名称已存在，请使用其他名称");}
    @ExceptionHandler(DataIntegrityViolationException.class) public ResponseEntity<?> referenced(Exception ex){return error(409,"REFERENCED","数据存在引用关系或约束冲突；被引用的基础数据可改为禁用");}
    @ExceptionHandler({CannotAcquireLockException.class,PessimisticLockingFailureException.class})
    public ResponseEntity<?> concurrent(Exception ex){return error(409,"CONCURRENT_CONFLICT","并发操作冲突，请刷新后重试");}
    @ExceptionHandler({CannotGetJdbcConnectionException.class}) public ResponseEntity<?> unavailable(Exception ex){return error(503,"DATABASE_UNAVAILABLE","数据库连接暂时不可用");}
    @ExceptionHandler(MethodArgumentNotValidException.class) public ResponseEntity<?> validation(MethodArgumentNotValidException ex) {
        var details=ex.getBindingResult().getFieldErrors().stream().map(e->Map.of("field",e.getField(),"message",Objects.toString(e.getDefaultMessage(),"参数无效"))).toList();
        return ResponseEntity.unprocessableEntity().body(Map.of("error",Map.of("code","VALIDATION_ERROR","message","请求参数校验失败","details",details)));
    }
    @ExceptionHandler({ConstraintViolationException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,MissingServletRequestParameterException.class})
    public ResponseEntity<?> badRequest(Exception ex){return error(422,"VALIDATION_ERROR","请求参数、字段或JSON格式无效");}
    @ExceptionHandler(NoResourceFoundException.class) public ResponseEntity<?> missing(Exception ex){return error(404,"HTTP_404","页面或资源不存在");}
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<?> database(Exception ex){log.error("Database operation failed",ex);return error(500,"DATABASE_ERROR","数据库操作失败");}
}

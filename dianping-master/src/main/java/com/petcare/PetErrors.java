package com.petcare;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.Map;
import static com.petcare.PetData.map;

@RestControllerAdvice(basePackages="com.petcare")
public class PetErrors {
    @ExceptionHandler(PetException.class)
    public ResponseEntity<Map<String,Object>> domain(PetException error) {
        return ResponseEntity.status(error.status).body(map("success",false,"errorMsg",error.getMessage()));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String,Object>> invalid(Exception error) {
        return ResponseEntity.badRequest().body(map("success",false,"errorMsg","请求格式不正确"));
    }
}

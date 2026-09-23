package com.quan.aicodehelper.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthCheck {
    /** 要求的角色；留空表示不校验角色（AuthInterceptor 直接放行）。 */
    String mustRole() default "";
}

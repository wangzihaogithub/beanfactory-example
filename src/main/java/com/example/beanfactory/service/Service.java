package com.example.beanfactory.service;

import com.example.beanfactory.util.ApplicationX.Component;

import java.lang.annotation.*;

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface Service {

	String value() default "";

}

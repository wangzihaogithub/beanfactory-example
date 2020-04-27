package com.example.beanfactory.dao;

import com.example.beanfactory.util.ApplicationX.Component;

import java.lang.annotation.*;

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface Repository {

	String value() default "";

}
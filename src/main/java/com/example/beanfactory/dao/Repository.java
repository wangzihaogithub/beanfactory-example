package com.example.beanfactory.dao;

import com.example.beanfactory.util.ApplicationX;

import java.lang.annotation.*;

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApplicationX.Component
public @interface Repository {

	String value() default "";

}
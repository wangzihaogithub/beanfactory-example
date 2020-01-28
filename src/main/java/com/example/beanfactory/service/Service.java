package com.example.beanfactory.service;

import com.example.beanfactory.util.ApplicationX;

import java.lang.annotation.*;

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApplicationX.Component
public @interface Service {

	String value() default "";

}

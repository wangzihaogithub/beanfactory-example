package com.example.beanfactory.controller;

import com.example.beanfactory.util.ApplicationX;

import java.lang.annotation.*;

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApplicationX.Component
public @interface Controller {

	String value() default "";

}
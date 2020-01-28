package com.example.beanfactory.config;

import com.example.beanfactory.util.ApplicationX;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApplicationX.Component
public @interface Configuration {

	String value() default "";

}

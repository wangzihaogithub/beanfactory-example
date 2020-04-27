package com.example.beanfactory.config;

import com.example.beanfactory.util.ApplicationX.Component;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface Configuration {

	String value() default "";

}

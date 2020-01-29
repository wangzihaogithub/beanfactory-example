package com.example.beanfactory.config;

import com.example.beanfactory.util.ApplicationX;

@ApplicationX.Component
@ApplicationX.Order(Integer.MIN_VALUE + 100)
public class MyBeanPostProcessor implements ApplicationX.BeanPostProcessor {

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws RuntimeException {
        System.out.println(Thread.currentThread()+" "+getClass().getSimpleName()
                + " postProcessBeforeInitialization("+beanName+")");
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws RuntimeException {
        System.out.println(Thread.currentThread()+" "+getClass().getSimpleName()
                + " postProcessAfterInitialization("+beanName+")");
        return bean;
    }
}

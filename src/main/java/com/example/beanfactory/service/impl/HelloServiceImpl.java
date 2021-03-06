package com.example.beanfactory.service.impl;

import com.example.beanfactory.dao.HelloRepository;
import com.example.beanfactory.entity.HelloPO;
import com.example.beanfactory.service.AbstractService;
import com.example.beanfactory.service.HelloService;
import com.example.beanfactory.service.SayHelloService;
import com.example.beanfactory.service.Service;
import com.example.beanfactory.util.ApplicationX.Autowired;

@Service
public class HelloServiceImpl extends AbstractService<HelloRepository, HelloPO,Integer> implements HelloService {
    //解决注入的循环依赖
    @Autowired
    private SayHelloService sayHelloService;

//    无法解决构造器循环依赖
//    public HelloServiceImpl(SayHelloService sayHelloService) {
//        this.sayHelloService = sayHelloService;
//    }
}

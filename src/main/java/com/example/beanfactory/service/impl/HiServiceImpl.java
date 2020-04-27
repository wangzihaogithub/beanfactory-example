package com.example.beanfactory.service.impl;

import com.example.beanfactory.dao.HelloRepository;
import com.example.beanfactory.entity.HelloPO;
import com.example.beanfactory.service.*;
import com.example.beanfactory.util.ApplicationX.Autowired;

@Service
public class HiServiceImpl extends AbstractService<HelloRepository, HelloPO,Integer> implements HiService {
    //解决注入的循环依赖
    @Autowired
    private HelloRepository helloRepository;

    private SayHelloService sayHelloService;
    private HelloService helloService;
    public HiServiceImpl(SayHelloService sayHelloService, HelloService helloService) {
        this.sayHelloService = sayHelloService;
        this.helloService = helloService;
    }

    @Override
    public SayHelloService getSayHelloService() {
        return sayHelloService;
    }

    @Override
    public HelloService getHelloService() {
        return helloService;
    }
}

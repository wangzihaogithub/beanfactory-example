package com.example.beanfactory.service.impl;

import com.example.beanfactory.dao.HelloRepository;
import com.example.beanfactory.entity.HelloPO;
import com.example.beanfactory.service.*;
import com.example.beanfactory.util.ApplicationX;
import com.example.beanfactory.util.ApplicationX.Autowired;
import com.example.beanfactory.util.ApplicationX.Qualifier;

import javax.sql.DataSource;

@Service
public class HiServiceImpl extends AbstractService<HelloRepository, HelloPO,Integer> implements HiService {
    //解决注入的循环依赖
    @Autowired
    private HelloRepository helloRepository;
    @Autowired
    @Qualifier("dataSource1")
    private DataSource dataSource2;
    private SayHelloService sayHelloService;
    private HelloService helloService;
    public HiServiceImpl(SayHelloService sayHelloService, HelloService helloService,
                         @Qualifier("dataSource2")
                         DataSource dataSource1) {
        this.sayHelloService = sayHelloService;
        this.helloService = helloService;
    }

    @Override
    public SayHelloService getSayHelloService() {
        return sayHelloService;
    }

    @Autowired
    public void setDataSource2(@Qualifier("dataSource1") DataSource dataSource2) {
        this.dataSource2 = dataSource2;
    }

    @Override
    public HelloService getHelloService() {
        return helloService;
    }
}

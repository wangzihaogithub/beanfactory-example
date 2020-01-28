package com.example.beanfactory.service.impl;

import com.example.beanfactory.dao.HelloRepository;
import com.example.beanfactory.entity.HelloPO;
import com.example.beanfactory.service.AbstractService;
import com.example.beanfactory.service.HelloService;
import com.example.beanfactory.service.Service;

@Service
public class HelloServiceImpl extends AbstractService<HelloRepository, HelloPO,Integer> implements HelloService {


}

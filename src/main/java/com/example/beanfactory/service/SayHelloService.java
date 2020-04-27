package com.example.beanfactory.service;

import com.example.beanfactory.entity.HelloPO;

import java.util.List;

public interface SayHelloService {
    HelloPO findById(Integer id);
    List<HelloPO> findList();

}

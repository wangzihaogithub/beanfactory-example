package com.example.beanfactory.dao;

import com.example.beanfactory.entity.AbstractPO;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.List;

public abstract class AbstractRepository<PO extends AbstractPO,ID extends Number> {
    public abstract PO findById(ID id);
    public abstract List<PO> findList();

    @PostConstruct
    public void init(){
        System.out.println(Thread.currentThread()+" "+getClass().getSimpleName() + " init");
    }

    @PreDestroy
    public void destroy() {
        System.out.println(Thread.currentThread()+" "+getClass().getSimpleName() + " destroy");
    }

}

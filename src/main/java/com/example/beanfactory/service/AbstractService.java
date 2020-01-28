package com.example.beanfactory.service;

import com.example.beanfactory.dao.AbstractRepository;
import com.example.beanfactory.entity.AbstractPO;
import com.example.beanfactory.util.ApplicationX;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.List;

public abstract class AbstractService <REPOSITORY extends AbstractRepository<PO,ID>,
                PO extends AbstractPO,
                ID extends Number> {
    @ApplicationX.Autowired
    private ApplicationX app;
    private REPOSITORY repository;

    public PO findById(ID id){
        return repository.findById(id);
    }

    public List<PO> findList(){
        return repository.findList();
    }

    @PostConstruct
    public void init(){
        System.out.println(Thread.currentThread()+" "+getClass().getSimpleName() + " init");
    }

    @PreDestroy
    public void destroy() {
        System.out.println(Thread.currentThread()+" "+getClass().getSimpleName() + " destroy");
    }

    @ApplicationX.Autowired
    public void setRepository(REPOSITORY repository) {
        this.repository = repository;
        System.out.println(Thread.currentThread()+" "+getClass().getSimpleName() + " setRepository(" + repository+")");
    }
}

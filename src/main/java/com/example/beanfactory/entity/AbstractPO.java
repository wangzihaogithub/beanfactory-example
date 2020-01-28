package com.example.beanfactory.entity;

public abstract class AbstractPO<ID>{
    public abstract ID getId();
    public abstract void setId(ID id);
}

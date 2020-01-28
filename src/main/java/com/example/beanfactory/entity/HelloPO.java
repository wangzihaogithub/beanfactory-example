package com.example.beanfactory.entity;

public class HelloPO extends AbstractPO<Integer>{
    private Integer id;
    private String name;

    public HelloPO() {
    }

    public HelloPO(Integer id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override
    public Integer getId() {
        return id;
    }

    @Override
    public void setId(Integer id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return "HelloPO{" +
                "id=" + id +
                ", name='" + name + '\'' +
                '}';
    }
}

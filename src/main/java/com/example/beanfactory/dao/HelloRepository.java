package com.example.beanfactory.dao;

import com.example.beanfactory.entity.HelloPO;
import com.example.beanfactory.util.ApplicationX;
import com.example.beanfactory.util.ApplicationX.*;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Repository("myHelloRepository")
public class HelloRepository extends AbstractRepository<HelloPO,Integer>{
    private final List<HelloPO> dataList = new ArrayList<>(3);
    private final Object testRequiredConstructor;
    private final ApplicationX app;
    public HelloRepository(
            @Autowired(required = false) Object object,
            @Autowired(required = true) ApplicationX app) {
        this.testRequiredConstructor = object;
        this.app = app;
    }

    @PostConstruct
    @Override
    public void init() {
        super.init();
        dataList.add(new HelloPO(1,"小王"));
        dataList.add(new HelloPO(2,"小红"));
        dataList.add(new HelloPO(3,"小李"));
    }

    @Override
    public HelloPO findById(Integer id) {
        for (HelloPO po : dataList) {
            if(Objects.equals(po.getId(),id)){
                return po;
            }
        }
        return null;
    }

    @Override
    public List<HelloPO> findList(){
        return Collections.unmodifiableList(dataList);
    }

}

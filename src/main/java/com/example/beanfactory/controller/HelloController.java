package com.example.beanfactory.controller;

import com.example.beanfactory.dao.HelloRepository;
import com.example.beanfactory.entity.HelloPO;
import com.example.beanfactory.service.HelloService;
import com.example.beanfactory.util.ApplicationX;

import javax.annotation.Resource;
import javax.sql.DataSource;
import java.util.List;

@Controller
public class HelloController implements ApplicationX.InitializingBean,ApplicationX.DisposableBean {
    private final HelloService helloService;

    @ApplicationX.Autowired
    @ApplicationX.Qualifier("myHelloRepository")
    private HelloRepository helloRepository;
    @Resource(name = "dataSource1")
    private DataSource dataSource;

    @Resource
    private ApplicationX app;

    public HelloController(HelloService helloService) {
        this.helloService = helloService;
    }

    public HelloPO findById(Integer id){
        return helloService.findById(id);
    }

    public List<HelloPO> findList(){
        return helloService.findList();
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        System.out.println(Thread.currentThread()+" "+getClass().getSimpleName() + " init");
    }

    @Override
    public void destroy() throws Exception {
        System.out.println(Thread.currentThread()+" "+getClass().getSimpleName() + " destroy");
    }
}

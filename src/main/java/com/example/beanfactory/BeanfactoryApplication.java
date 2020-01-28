package com.example.beanfactory;

import com.example.beanfactory.controller.HelloController;
import com.example.beanfactory.entity.HelloPO;
import com.example.beanfactory.util.ApplicationX;

import java.util.List;

/**
 * 这是一个示例项目, 演示了这个小型容器的基本功能. 你可以通过它去学习spring的容器相关.
 *
 * 特色 : 这个容器只需要一个类即可{@link ApplicationX), 只依赖jdk, 不依赖任何包.
 *
 * 这个文件{@link ApplicationX}是从 https://github.com/wangzihaogithub/spring-boot-protocol 项目中单拉出来的.
 *
 * @see com.example.beanfactory.util.ApplicationX
 * @author wangzihao
 * @date 2020年1月28日23:33:55
 */
public class BeanfactoryApplication {

    public static void main(String[] args) {
        ApplicationX app = new ApplicationX();
        app.scanner("com.example.beanfactory");

        System.out.println();
        System.out.println("applicationX = " + app);

        newTestIOThread(app).start();
    }

    /**
     * 模拟http请求, IO线程
     * @param app ApplicationX
     * @return 一个线程
     */
    private static Thread newTestIOThread(ApplicationX app){
        System.out.println();
        return new Thread("IO-Thread"){
            @Override
            public void run() {
                HelloController controller = app.getBean(HelloController.class);
                for (int i = 1; i <= 3; i++) {
                    HelloPO helloPO = controller.findById(i);
                    System.out.println(Thread.currentThread()+" findById = " + helloPO);

                    List<HelloPO> helloPOList = controller.findList();
                    System.out.println(Thread.currentThread()+" findList = " + helloPOList);
                    System.out.println();

                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        };
    }
}

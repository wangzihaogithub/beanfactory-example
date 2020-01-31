### 这是简化版spring容器的示例项目, 演示了这个小型容器的基本功能.(其实它已经基本完全实现了spring的容器骨架), 你可以通过它去学习spring的容器相关

* 特色 : 这个容器只有一个类{@link com.example.beanfactory.util.ApplicationX), 只依赖jdk.

* 查看具体实现 com.example.beanfactory.util.ApplicationX

* 查看测试用例 com.example.beanfactory.BeanfactoryApplication.main()

* 这个文件{@link com.example.beanfactory.util.ApplicationX}是从 [https://github.com/wangzihaogithub/spring-boot-protocol](https://github.com/wangzihaogithub/spring-boot-protocol) 项目中单拉出来的.

 ---
 
### 介绍关键类 (与spring一致)

* BeanDefinition (描述bean, 等同于class. 因为class描述不了更多的信息, 所以要用BeanDefinition包装一下)

* BeanPostProcessor (它是bean在各个声明周期阶段的回调接口)

* InjectElement (它实现了自动注入的具体逻辑)

### 介绍Bean创建流程 (与spring一致)

* **首先需要添加BeanDefinition** (可以用户主动添加 或扫描文件自动添加)

* **newInstance阶段**(new 操作)

    newInstance之前的回调接口. postProcessBeforeInstantiation(beanName, BeanDefinition) 
    
    选择可用构造器的回调接口. determineCandidateConstructors(beanClass,beanName)
        
    发生了newInstance调用,获得了实例
    
    通知合成bean配置的回调接口. postProcessMergedBeanDefinition(bean,beanName). 注: 合成bean是一个bean里，会创建多个子孙bean. 例如@Bean注解的实现
    
    newInstance之后的回调接口. postProcessAfterInstantiation(bean,beanName)
    
* **填充bean属性阶段**(自动注入) 

  获取填充属性的的回调接口. postProcessProperties(PropertyValues,bean,beanName)
  
  获取填充数据的的回调接口. postProcessPropertyValues(PropertyValues,PropertyDescriptor[],bean,beanName)
  
  这时发生了填充属性数据,bean里面的属性被填充完毕.
  
* **初始化bean阶段**
  
  先将所有实现注入接口执行一遍 (Aware接口)
  
  初始化前的回调接口. postProcessBeforeInitialization(bean,beanName)
  
  这时发生了初始化方法的调用 (InitializingBean接口与@PostConstruct方法)
  
  初始化后的回调接口. postProcessAfterInitialization(bean,beanName)

* **如果是单例,则将bean保存到单例map中**

* **结束流程**

 ---

### 介绍关键方法 (与spring一致)

* bean声明与创建

 ![](image/bean声明与创建.jpg)
 
* bean扫描过程

 ![](image/bean扫描过程.jpg)

 --- 
 
### spring介绍

 - **spring循环依赖的处理**


        循环依赖指 : bean在创建时, 因自动注入字段而触发的子bean又依赖正在创建的父bean. 
        这时父bean还没创建完, 找不到父bean就会报错.
        
        解决方式 : 把正在创建的bean放到一个临时的map中, 这样子bean就能获取到了, 就解决了. 注: 正在创建的可能是原型bean,也可能是单例bean.
        
        spring的具体实现 : 如果当期处于正在创建bean中时,从临时map中获取.
 
 
  ---
  
作者邮箱 : 842156727@qq.com

github地址 : [https://github.com/wangzihaogithub/beanfactory-example](https://github.com/wangzihaogithub/beanfactory-example)


package com.example.beanfactory.util;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.annotation.*;
import java.lang.reflect.*;
import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.lang.annotation.ElementType.*;

/**
 * Lightweight container that supports resource injection
 * @author wangzihao
 *  2016/11/11/011
 */
public class ApplicationX {
    private static final AtomicInteger SHUTDOWN_HOOKID_INCR = new AtomicInteger();
    private static final Method[] EMPTY_METHOD_ARRAY = {};
    private static final PropertyDescriptor[] EMPTY_DESCRIPTOR_ARRAY = {};
    private static final Constructor<ConcurrentMap> CONCURRENT_REFERENCE_MAP_CONSTRUCTOR = getAnyConstructor(
            new Class[]{int.class},
            "com.github.netty.core.util.ConcurrentReferenceHashMap",
            "org.springframework.util.ConcurrentReferenceHashMap",
            "org.hibernate.validator.internal.util.ConcurrentReferenceHashMap"
    );
    private static final Map<Class,Boolean> AUTOWIRED_ANNOTATION_CACHE_MAP = newConcurrentReferenceMap(128);
    private static final Map<Class,Boolean> QUALIFIER_ANNOTATION_CACHE_MAP = newConcurrentReferenceMap(128);
    private static final Map<Class,PropertyDescriptor[]> PROPERTY_DESCRIPTOR_CACHE_MAP = newConcurrentReferenceMap(128);
    private static final Map<Class, Method[]> DECLARED_METHODS_CACHE = newConcurrentReferenceMap(128);

    private Supplier<ClassLoader> resourceLoader;
    private Function<BeanDefinition,String> beanNameGenerator = new DefaultBeanNameGenerator(this);

    private final Collection<Class<? extends Annotation>> initMethodAnnotations = new LinkedHashSet<>(
                Arrays.asList(PostConstruct.class));
    private final Collection<Class<? extends Annotation>> destroyMethodAnnotations = new LinkedHashSet<>(
                Arrays.asList(PreDestroy.class));
    private final Collection<Class<? extends Annotation>> scannerAnnotations = new LinkedHashSet<>(
                Arrays.asList(Resource.class,Component.class));
    private final Collection<Class<? extends Annotation>> autowiredAnnotations = new LinkedHashSet<>(
                Arrays.asList(Resource.class,Autowired.class));
    private final Collection<Class<? extends Annotation>> qualifierAnnotations = new LinkedHashSet<>(
                Arrays.asList(Resource.class,Qualifier.class));
    private final Collection<Class<? extends Annotation>> orderedAnnotations = new LinkedHashSet<>(
                Arrays.asList(Order.class));
    private final Collection<String> beanSkipLifecycles = new LinkedHashSet<>(8);
    private final Collection<BeanPostProcessor> beanPostProcessors = new TreeSet<>(new OrderComparator(orderedAnnotations));
    private final Map<String,BeanDefinition> beanDefinitionMap = new ConcurrentHashMap<>(64);
    private final Map<Class,String[]> beanNameMap = new ConcurrentHashMap<>(64);
    private final Map<String,Object> beanInstanceMap = new ConcurrentHashMap<>(64);
    private final Map<Class, AbstractBeanFactory> beanFactoryMap = new LinkedHashMap<>(8);
    private final AbstractBeanFactory defaultBeanFactory = new DefaultBeanFactory();
    private final Scanner scanner = new Scanner();

    public ApplicationX() {
        this(ApplicationX.class::getClassLoader);
    }

    public ApplicationX(Supplier<ClassLoader> resourceLoader) {
        this.resourceLoader = Objects.requireNonNull(resourceLoader);
        addClasses(initMethodAnnotations,
                "javax.annotation.PostConstruct");
        addClasses(destroyMethodAnnotations,
                "javax.annotation.PreDestroy");
        addClasses(scannerAnnotations,
                "javax.annotation.Resource",
                "org.springframework.stereotype.Component");
        addClasses(autowiredAnnotations,
                "javax.annotation.Resource",
                "javax.inject.Inject",
                "org.springframework.beans.factory.annotation.Autowired");
        addClasses(qualifierAnnotations,
                "javax.annotation.Resource",
                "org.springframework.beans.factory.annotation.Qualifier");
        addClasses(orderedAnnotations,
                "org.springframework.core.annotation.Order");
        addInstance(this);
        addBeanPostProcessor(new RegisteredBeanPostProcessor(this));
        addBeanPostProcessor(new AutowiredConstructorPostProcessor(this));
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownHook,"app.shutdownHook-"+SHUTDOWN_HOOKID_INCR.getAndIncrement()));
    }

    public static void main(String[] args) throws Exception {
        long startTime = System.currentTimeMillis();
        ApplicationX app = new ApplicationX();
        System.out.println("new = " + (System.currentTimeMillis() - startTime)+"/ms");

        startTime = System.currentTimeMillis();
        int count = app.scanner("com.github.netty");
        System.out.println("scanner = " + (System.currentTimeMillis() - startTime)+"/ms");

        System.out.println("count = " + count);
        System.out.println("app = " + app);
    }

    private void addClasses(Collection annotationList, String... classNames){
        ClassLoader classLoader = resourceLoader.get();
        for (String className : classNames) {
            try {
                annotationList.add(Class.forName(className,false, classLoader));
            } catch (Exception e) {
                //skip
            }
        }
    }

    public Object addInstance(Object instance){
        return addInstance(instance,true);
    }

    public Object addInstance(Object instance,boolean isLifecycle){
        return addInstance(null,instance,isLifecycle);
    }

    public Object addInstance(String beanName,Object instance,boolean isLifecycle){
        Class beanType = instance.getClass();
        BeanDefinition definition = newBeanDefinition(beanType);
        definition.setBeanSupplier(()->instance);
        if(!isLifecycle) {
            beanSkipLifecycles.add(beanName);
        }
        if(beanName == null){
            beanName = beanNameGenerator.apply(definition);
        }
        addBeanDefinition(beanName,definition);
        Object oldInstance = beanInstanceMap.remove(beanName, instance);
        getBean(beanName,null,true);
        return oldInstance;
    }

    public int scanner(ClassLoader classLoader){
        Map<Class,Boolean> scannerAnnotationCacheMap = new ConcurrentHashMap<>();
        AtomicInteger classCount = new AtomicInteger();
        try {
            for(String rootPackage : scanner.getRootPackages()){
                scanner.doScan(rootPackage,classLoader,(className)->{
                    try {
                        Class clazz = Class.forName(className,false, classLoader);
                        if (clazz.isAnnotation()) {
                            return;
                        }
                        // TODO: 1月27日 027  doScan skip interface impl by BeanPostProcessor
                        if(clazz.isInterface()){
                            return;
                        }
                        if(!isExistAnnotation(clazz, scannerAnnotations, scannerAnnotationCacheMap)) {
                            return;
                        }
                        BeanDefinition definition = newBeanDefinition(clazz);
                        String beanName = beanNameGenerator.apply(definition);
                        addBeanDefinition(beanName,definition);
                        classCount.incrementAndGet();
                    } catch (ReflectiveOperationException | LinkageError e) {
                        //skip
                    }
                });
            }
            if(classCount.get() > 0) {
                LinkedList<String> beanNameList = new LinkedList<>();
                for (Map.Entry<String, BeanDefinition> entry : beanDefinitionMap.entrySet()) {
                    String beanName = entry.getKey();
                    BeanDefinition definition = entry.getValue();
                    if (!definition.isLazyInit() && definition.isSingleton()) {
                        if(BeanPostProcessor.class.isAssignableFrom(definition.getBeanClass())) {
                            beanNameList.addFirst(beanName);
                        }else {
                            beanNameList.addLast(beanName);
                        }
                    }
                }

                for (String beanName : beanNameList) {
                    getBean(beanName, null, true);
                }
            }
            return classCount.get();
        } catch (Exception e) {
            throw new IllegalStateException("scanner error="+e,e);
        }
    }

    public int scanner(String... rootPackage){
        addScanPackage(rootPackage);
        ClassLoader loader = resourceLoader.get();
        return scanner(loader);
    }

    public ApplicationX addExcludesPackage(String... excludesPackages){
        if(excludesPackages != null) {
            scanner.getExcludes().addAll(Arrays.asList(excludesPackages));
        }
        return this;
    }

    public ApplicationX addScanPackage(String...rootPackages){
        if(rootPackages != null) {
            scanner.getRootPackages().addAll(Arrays.asList(rootPackages));
        }
        return this;
    }

    public ApplicationX removeScanPackage(String...rootPackages){
        if(rootPackages != null) {
            scanner.getRootPackages().removeAll(Arrays.asList(rootPackages));
        }
        return this;
    }

    public ApplicationX addBeanPostProcessor(BeanPostProcessor beanPostProcessor){
        beanPostProcessors.add(beanPostProcessor);
        return this;
    }

    public ApplicationX addBeanFactory(Class type, AbstractBeanFactory beanFactory){
        addInstance(beanFactory,true);
        beanFactoryMap.put(type,beanFactory);
        return this;
    }

    public BeanDefinition[] getBeanDefinitions(Class clazz){
        String[] beanNames = beanNameMap.get(clazz);
        BeanDefinition[] beanDefinitions = new BeanDefinition[beanNames.length];
        for (int i = 0; i < beanNames.length; i++) {
            beanDefinitions[i] = getBeanDefinition(beanNames[i]);
        }
        return beanDefinitions;
    }

    public BeanDefinition getBeanDefinition(String beanName) {
        BeanDefinition definition = beanDefinitionMap.get(beanName);
        return definition;
    }

    public String[] getBeanNamesForType(Class clazz) {
        Collection<String> result = new ArrayList<>();
        for (Map.Entry<String,BeanDefinition> entry : beanDefinitionMap.entrySet()) {
            BeanDefinition definition = entry.getValue();
            if(clazz.isAssignableFrom(definition.getBeanClassIfResolve(resourceLoader))){
                String beanName = entry.getKey();
                result.add(beanName);
            }
        }
        return result.toArray(new String[0]);
    }

    public <T>T getBean(Class<T> clazz) {
        return getBean(clazz, null,true);
    }

    public <T>T getBean(Class<T> clazz,Object[] args,boolean required) {
        String[] beanNames = getBeanNamesForType(clazz);
        String beanName;
        if(beanNames.length == 0){
            if(required){
                throw new IllegalStateException("Not found bean. "+Arrays.toString(beanNames));
            }else {
                return null;
            }
        }else if(beanNames.length == 1){
            beanName = beanNames[0];
        }else {
            beanName = null;
            for (String eachBeanName : beanNames) {
                BeanDefinition definition = getBeanDefinition(eachBeanName);
                if(definition.isPrimary()){
                    if(beanName == null){
                        beanName = eachBeanName;
                    }else {
                        throw new IllegalStateException("Found more primary bean. "+Arrays.toString(beanNames));
                    }
                }else {
                    //后面的bean覆盖前面的bean
                    beanName = eachBeanName;
                }
            }
        }
        return getBean(beanName,args,required);
     }

    public <T>T getBean(String beanName,Object[] args,boolean required){
        BeanDefinition definition = beanDefinitionMap.get(beanName);
        if(definition == null) {
            if(required) {
                throw new IllegalStateException("getBean error. bean is not definition. beanName=" + beanName);
            }else {
                return null;
            }
        }
        Object instance = definition.isSingleton()? beanInstanceMap.get(beanName): null;
        if(instance == null) {
            Class beanClass = definition.getBeanClassIfResolve(resourceLoader);
            AbstractBeanFactory beanFactory = getBeanFactory(beanClass);
            instance = beanFactory.createBean(beanName,definition,args);
        }
        if(definition.isSingleton()){
            beanInstanceMap.put(beanName, instance);
        }
        return (T) instance;
    }

    public <T>T getBean(String beanName){
        return (T) getBean(beanName,null,true);
    }

    public <T>T getBean(String beanName,Object[] args){
        return (T) getBean(beanName,args,true);
    }

    public <T>List<T> getBeanForAnnotation(Class<? extends Annotation>... annotationType){
        List<T> result = new ArrayList<>();
        for (Map.Entry<String, BeanDefinition> entry : beanDefinitionMap.entrySet()) {
            String beanName = entry.getKey();
            BeanDefinition definition = entry.getValue();
            Class beanClass = definition.getBeanClassIfResolve(resourceLoader);
            Annotation annotation = findAnnotation(beanClass,Arrays.asList(annotationType));
            if(annotation != null) {
                T bean = getBean(beanName, null,false);
                if(bean != null) {
                    result.add(bean);
                }
            }
        }
        return result;
    }

    public <T>List<T> getBeanForType(Class<T> clazz){
        List<T> result = new ArrayList<>();
        for (String beanName : getBeanNamesForType(clazz)) {
            T bean = getBean(beanName, null,false);
            if(bean != null) {
                result.add(bean);
            }
        }
        return result;
    }

    private Object initializeBean(String beanName, BeanWrapper beanWrapper, BeanDefinition definition) throws IllegalStateException{
        Object bean = beanWrapper.getWrappedInstance();
        invokeBeanAwareMethods(beanName,bean,definition);
        Object wrappedBean = bean;
        wrappedBean = applyBeanBeforeInitialization(beanName,wrappedBean);
        invokeBeanInitialization(beanName,bean,definition);
        wrappedBean = applyBeanAfterInitialization(beanName,wrappedBean);
        return wrappedBean;
    }

    private void invokeBeanAwareMethods(String beanName, Object bean, BeanDefinition definition) throws IllegalStateException{
        if(bean instanceof Aware){
            if(bean instanceof BeanNameAware){
                ((BeanNameAware) bean).setBeanName(beanName);
            }
            if(bean instanceof ApplicationAware){
                ((ApplicationAware) bean).setApplication(this);
            }
        }
    }

    private Object applyBeanBeforeInitialization(String beanName, Object bean) throws IllegalStateException{
        Object result = bean;
        for (BeanPostProcessor processor : new ArrayList<>(beanPostProcessors)) {
            Object current;
            try {
                current = processor.postProcessBeforeInitialization(result, beanName);
            } catch (Exception e) {
                throw new IllegalStateException("applyBeanBeforeInitialization error="+e, e);
            }
            if (current == null) {
                return result;
            }
            result = current;
        }
        return result;
    }

    private Object applyBeanAfterInitialization(String beanName, Object bean) throws IllegalStateException{
        Object result = bean;
        for (BeanPostProcessor processor : new ArrayList<>(beanPostProcessors)) {
            Object current;
            try {
                current = processor.postProcessAfterInitialization(result, beanName);
            } catch (Exception e) {
                throw new IllegalStateException("applyBeanAfterInitialization error="+e, e);
            }
            if (current == null) {
                return result;
            }
            result = current;
        }
        return result;
    }

    public BeanDefinition newBeanDefinition(Class beanType){
        Lazy lazyAnnotation = (Lazy) beanType.getAnnotation(Lazy.class);
        Scope scopeAnnotation = (Scope) beanType.getAnnotation(Scope.class);
        Primary primaryAnnotation = (Primary) beanType.getAnnotation(Primary.class);

        BeanDefinition definition = new BeanDefinition();
        definition.setBeanClass(beanType);
        definition.setBeanClassName(beanType.getName());
        definition.setScope(scopeAnnotation == null? BeanDefinition.SCOPE_SINGLETON : scopeAnnotation.value());
        definition.setLazyInit(lazyAnnotation != null && lazyAnnotation.value());
        definition.setInitMethodName(findMethodNameByNoArgs(beanType,initMethodAnnotations));
        definition.setDestroyMethodName(findMethodNameByNoArgs(beanType,destroyMethodAnnotations));
        definition.setPrimary(primaryAnnotation != null);
        return definition;
    }

    public BeanDefinition addBeanDefinition(String beanName,BeanDefinition definition){
        return addBeanDefinition(beanName,definition,beanNameMap,beanDefinitionMap);
    }

    public BeanDefinition addBeanDefinition(String beanName,BeanDefinition definition,
                                            Map<Class,String[]> beanNameMap,
                                            Map<String,BeanDefinition> beanDefinitionMap){
        Class beanClass = definition.getBeanClassIfResolve(resourceLoader);
        String[] oldBeanNames = beanNameMap.get(beanClass);
        Set<String> nameSet = oldBeanNames != null? new LinkedHashSet<>(Arrays.asList(oldBeanNames)):new LinkedHashSet<>(1);
        nameSet.add(beanName);

        beanNameMap.put(beanClass,nameSet.toArray(new String[0]));
        return beanDefinitionMap.put(beanName,definition);
    }

    private AbstractBeanFactory getBeanFactory(Class beanType){
        AbstractBeanFactory beanFactory = null;
        if(beanFactoryMap.size() > 0) {
            for (Class type = beanType; type != null; type = type.getSuperclass()) {
                beanFactory = beanFactoryMap.get(type);
                if (beanFactory != null) {
                    break;
                }
            }
        }
        if(beanFactory == null){
            beanFactory = defaultBeanFactory;
        }
        return beanFactory;
    }

    private static boolean isAbstract(Class clazz){
        int modifier = clazz.getModifiers();
        return Modifier.isInterface(modifier) || Modifier.isAbstract(modifier);
    }

    private static Boolean isExistAnnotation0(Class clazz, Collection<Class<? extends Annotation>> finds,Map<Class,Boolean> cacheMap){
        Annotation annotation;
        Boolean exist = cacheMap.get(clazz);
        if(finds.contains(clazz)){
            exist = Boolean.TRUE;
        }else if(exist == null){
            exist = Boolean.FALSE;
            cacheMap.put(clazz,exist);
            Queue<Annotation> queue = new LinkedList<>(Arrays.asList(clazz.getDeclaredAnnotations()));
            while ((annotation = queue.poll()) != null){
                Class<? extends Annotation> annotationType = annotation.annotationType();
                if(annotationType == clazz){
                    continue;
                }
                if(finds.contains(annotationType)){
                    exist = Boolean.TRUE;
                    break;
                }
                if(isExistAnnotation0(annotationType,finds,cacheMap)){
                    exist = Boolean.TRUE;
                    break;
                }
            }
        }
        cacheMap.put(clazz,exist);
        return exist;
    }

    private static boolean isExistAnnotation(Class clazz, Collection<Class<? extends Annotation>> finds,Map<Class,Boolean> cacheMap){
        Boolean existAnnotation = cacheMap.get(clazz);
        if(existAnnotation == null){
            Map<Class,Boolean> tempCacheMap = new HashMap<>();
            existAnnotation = isExistAnnotation0(clazz, finds, tempCacheMap);
            cacheMap.putAll(tempCacheMap);
        }
        return existAnnotation;
    }

//    private static Unsafe UNSAFE;
//    static {
//        try {
//            Field f = Unsafe.class.getDeclaredField("theUnsafe");
//            f.setAccessible(true);
//            UNSAFE =(Unsafe)f.get(null);
//        } catch (Exception e) {
//            //
//        }
//    }

    @Override
    public String toString() {
        return scanner.getRootPackages() +" @ size = " + beanDefinitionMap.size();
    }

    /**
     * 1.扫描class文件
     * 2.创建对象并包装
     */
    public static class Scanner {
        private final Collection<String> rootPackages = new ArrayList<>(6);
        private final Collection<String> excludes = new LinkedHashSet<>(6);
        public Collection<String> getRootPackages() {
            return rootPackages;
        }
        public Collection<String> getExcludes(){
            return this.excludes;
        }

        public void doScan(String basePackage,ClassLoader loader, Consumer<String> classConsumer) throws IOException {
            StringBuilder buffer = new StringBuilder();
            String splashPath = dotToSplash(basePackage);
            URL url = loader.getResource(splashPath);
            if (url == null || existContains(url)) {
                return;
            }
            String filePath = getRootPath(url);
            List<String> names;
            if (isJarFile(filePath)) {
                names = readFromJarFile(filePath, splashPath);
            } else {
                names = readFromDirectory(filePath);
            }

            for (String name : names) {
                if (isClassFile(name)) {
                    String className = toClassName(buffer, name, basePackage);
                    classConsumer.accept(className);
                } else {
                    doScan(basePackage + "." + name, loader,classConsumer);
                }
            }
        }

        private boolean existContains(URL url){
            if(excludes.isEmpty()) {
                return false;
            }
            String[] urlStr = url.getPath().split("/");
            for(String s : excludes) {
                for(String u :urlStr) {
                    if (u.equals(s)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private String toClassName(StringBuilder buffer,String shortName, String basePackage) {
            buffer.setLength(0);
            shortName = trimExtension(shortName);
            if(shortName.contains(basePackage)) {
                buffer.append(shortName);
            } else {
                buffer.append(basePackage);
                buffer.append('.');
                buffer.append(shortName);
            }
            return buffer.toString();
        }

//        if(jarPath.equals("/git/api/erp.jar"))
//        jarPath = "git/api/erp.jar";
        private List<String> readFromJarFile(String jarPath, String splashedPackageName) throws IOException {
            JarInputStream jarIn = new JarInputStream(new FileInputStream(jarPath));
            JarEntry entry = jarIn.getNextJarEntry();

            List<String> nameList = new ArrayList<String>();
            while (null != entry) {
                String name = entry.getName();
                if (name.startsWith(splashedPackageName) && isClassFile(name)) {
                    nameList.add(name);
                }
                entry = jarIn.getNextJarEntry();
            }
            return nameList;
        }

        private List<String> readFromDirectory(String path) {
            File file = new File(path);
            String[] names = file.list();
            if (null == names) {
                return Collections.emptyList();
            }
            return Arrays.asList(names);
        }

        private boolean isClassFile(String name) {
            return name.endsWith(".class");
        }

        private boolean isJarFile(String name) {
            return name.endsWith(".jar");
        }

        private String getRootPath(URL url) {
            String fileUrl = url.getFile();
            int pos = fileUrl.indexOf('!');
            if (-1 == pos) {
                return fileUrl;
            }
            return fileUrl.substring(5, pos);
        }

        /**
         * "cn.fh.lightning" -> "cn/fh/lightning"
         */
        private String dotToSplash(String name) {
            return name.replaceAll("\\.", "/");
        }

        /**
         * "com/git/Apple.class" -> "com.git.Apple"
         */
        private String trimExtension(String name) {
            int pos = name.indexOf('.');
            if (-1 != pos) {
                name = name.substring(0, pos);
            }
            return name.replace("/",".");
        }

        /**
         * /application/home -> /home
         */
        private String trimURI(String uri) {
            String trimmed = uri.substring(1);
            int splashIndex = trimmed.indexOf('/');
            return trimmed.substring(splashIndex);
        }

        @Override
        public String toString() {
            return "Scanner{" +
                    "rootPackages=" + rootPackages +
                    ", excludes=" + excludes +
                    '}';
        }
    }

    protected int findAutowireType(AnnotatedElement field){
        int autowireType;
        Annotation qualifierAnnotation = findDeclaredAnnotation(field, qualifierAnnotations,QUALIFIER_ANNOTATION_CACHE_MAP);
        if(qualifierAnnotation != null){
            if("Resource".equals(qualifierAnnotation.annotationType().getSimpleName())){
                String autowiredBeanName = getAnnotationValue(qualifierAnnotation, "value", String.class);
                autowireType = (autowiredBeanName == null || autowiredBeanName.isEmpty())?
                        BeanDefinition.AUTOWIRE_BY_TYPE : BeanDefinition.AUTOWIRE_BY_TYPE;
            }else {
                autowireType = BeanDefinition.AUTOWIRE_BY_NAME;
            }
        }else {
            autowireType = BeanDefinition.AUTOWIRE_BY_TYPE;
        }
        return autowireType;
    }

    /**
     * 参考 org.springframework.beans.factory.annotation.InjectedElement
     * @param <T> 成员
     */
    public static class InjectElement<T extends Member>{
        private final T member;
        private final ApplicationX applicationX;
        private final Annotation autowiredAnnotation;
        private final int[] autowireType;
        private final Boolean[] requireds;
        private Type[] requiredType;
        private Class[] requiredClass;
        private String[] requiredName;
        private Boolean required;
        public InjectElement(T member, ApplicationX applicationX, Annotation autowiredAnnotation, int[] autowireType, Boolean[] requireds) {
            this.member = member;
            this.applicationX = applicationX;
            this.autowiredAnnotation = autowiredAnnotation;
            this.autowireType = autowireType;
            this.requireds = requireds;
        }

        public InjectElement(Executable executable, ApplicationX applicationX){
            int parameterCount = executable.getParameterCount();
            this.member = (T) executable;
            this.applicationX = applicationX;
            this.autowiredAnnotation = findDeclaredAnnotation(executable, applicationX.autowiredAnnotations, AUTOWIRED_ANNOTATION_CACHE_MAP);
            this.autowireType = new int[parameterCount];
            this.requiredClass = new Class[parameterCount];
            this.requiredType = new Type[parameterCount];
            this.requiredName = new String[parameterCount];
            this.requireds = new Boolean[parameterCount];

            Parameter[] parameters = executable.getParameters();
            for (int i = 0; i < parameterCount; i++) {
                Parameter parameter = parameters[i];
                this.requiredClass[i] = parameter.getType();
                this.autowireType[i] = applicationX.findAutowireType(parameter);
                switch (this.autowireType[i]){
                    case BeanDefinition.AUTOWIRE_BY_TYPE:{
                        Annotation parameterInjectAnnotation = findDeclaredAnnotation(parameter, applicationX.autowiredAnnotations, AUTOWIRED_ANNOTATION_CACHE_MAP);
                        this.requiredType[i] = findAnnotationDeclaredType(parameterInjectAnnotation,parameter.getParameterizedType());
                        break;
                    }
                    case BeanDefinition.AUTOWIRE_BY_NAME:{
                        Annotation qualifierAnnotation = findDeclaredAnnotation(parameter, applicationX.qualifierAnnotations, QUALIFIER_ANNOTATION_CACHE_MAP);
                        String autowiredBeanName = qualifierAnnotation != null?
                                getAnnotationValue(qualifierAnnotation, "value", String.class) : parameter.getName();
                        this.requiredName[i] = autowiredBeanName;
                        break;
                    }
                    default:{
                        break;
                    }
                }
                Annotation parameterAutowiredAnnotation = findDeclaredAnnotation(parameter, applicationX.autowiredAnnotations, AUTOWIRED_ANNOTATION_CACHE_MAP);
                this.requireds[i] = parameterAutowiredAnnotation != null?
                        getAnnotationValue(parameterAutowiredAnnotation, "required", Boolean.class) : null;
            }
            if (this.autowiredAnnotation != null) {
                this.required = getAnnotationValue(this.autowiredAnnotation, "required", Boolean.class);
            }
        }

        public InjectElement(Field field,ApplicationX applicationX){
            this.member = (T) field;
            this.applicationX = applicationX;
            this.autowiredAnnotation = findDeclaredAnnotation(field, applicationX.autowiredAnnotations, AUTOWIRED_ANNOTATION_CACHE_MAP);
            this.autowireType = new int[]{applicationX.findAutowireType(field)};
            this.requiredClass = new Class[]{field.getType()};
            switch (this.autowireType[0]){
                case BeanDefinition.AUTOWIRE_BY_TYPE:{
                    this.requiredType = new Type[]{findAnnotationDeclaredType(this.autowiredAnnotation,field.getGenericType())};
                    break;
                }
                case BeanDefinition.AUTOWIRE_BY_NAME:{
                    Annotation qualifierAnnotation = findDeclaredAnnotation(field, applicationX.qualifierAnnotations, QUALIFIER_ANNOTATION_CACHE_MAP);
                    String autowiredBeanName = qualifierAnnotation != null?
                            getAnnotationValue(qualifierAnnotation, "value", String.class) : field.getName();
                    this.requiredName = new String[]{autowiredBeanName};
                    break;
                }
                default:{
                    break;
                }
            }
            if (this.autowiredAnnotation != null) {
                this.required = getAnnotationValue(this.autowiredAnnotation, "required", Boolean.class);
            }
            this.requireds = new Boolean[]{this.required};
        }

        public static List<InjectElement<Field>> getInjectFields(Class rootClass, ApplicationX applicationX){
            List<InjectElement<Field>> list = new ArrayList<>();
            for(Class clazz = rootClass; clazz != null && clazz!=Object.class; clazz = clazz.getSuperclass()) {
                for (Field field : clazz.getDeclaredFields()) {
                    if(null != findDeclaredAnnotation(field, applicationX.autowiredAnnotations, AUTOWIRED_ANNOTATION_CACHE_MAP)){
                        InjectElement<Field> element = new InjectElement<>(field, applicationX);
                        list.add(element);
                    }
                }
            }
            return list;
        }

        public static List<InjectElement<Method>> getInjectMethods(Class rootClass,ApplicationX applicationX){
            List<InjectElement<Method>> result = new ArrayList<>();
            eachClass(rootClass, clazz -> {
                for (Method method : getDeclaredMethods(clazz)) {
                    if(method.getParameterCount() > 0
                            && null != findDeclaredAnnotation(method, applicationX.autowiredAnnotations, AUTOWIRED_ANNOTATION_CACHE_MAP)){
                        result.add(new InjectElement<>(method, applicationX));
                    }
                }
            });
            return result;
        }

        private Object[] getInjectValues(Class targetClass) throws IllegalStateException{
            Boolean defaultRequired = this.required;
            if(defaultRequired == null){
                defaultRequired = Boolean.FALSE;
            }

            Object[] values = new Object[autowireType.length];
            for (int i = 0; i < autowireType.length; i++) {
                Object injectResource;
                Boolean required =  requireds[i];
                if(required == null){
                    required = defaultRequired;
                }
                Object desc;
                switch (autowireType[i]){
                    case BeanDefinition.AUTOWIRE_BY_NAME:{
                        desc = requiredName[i];
                        injectResource = applicationX.getBean(requiredName[i], null, false);
                        break;
                    }
                    case BeanDefinition.AUTOWIRE_BY_TYPE:
                    default:{
                        Class autowireClass = requiredType[i] instanceof Class?
                                    (Class)requiredType[i] : findConcreteClass(requiredClass[i],targetClass);
                        desc = autowireClass;
                        if(autowireClass == Object.class){
                            injectResource = null;
                        }else if(isAbstract(autowireClass)) {
                            List implList = applicationX.getBeanForType(autowireClass);
                            injectResource = implList.isEmpty()? null: implList.get(0);
                        }else {
                            injectResource = applicationX.getBean(autowireClass,null,false);
                        }
                        break;
                    }
                }
                if(injectResource == null && required){
                    throw new IllegalStateException("Required part["+(i+1)+"] '"+desc+"' is not present. member='"+member+"',class="+member.getDeclaringClass()+". Dependency annotations: Autowired(required=false)");
                }
                values[i] = injectResource;
            }
            return values;
        }

        private static Class findConcreteClass(Class<?> parameterGenericClass, Class concreteChildClass){
            BiFunction<Type,Class<?>,Class<?>> findFunction = (generic,genericSuper)->{
                if(generic instanceof ParameterizedType){
                    for (Type actualTypeArgument : ((ParameterizedType) generic).getActualTypeArguments()) {
                        if(actualTypeArgument instanceof Class
                                && genericSuper.isAssignableFrom((Class<?>) actualTypeArgument)){
                            return (Class) actualTypeArgument;
                        }
                    }
                }
                return null;
            };
            Class<?> result = findFunction.apply(concreteChildClass.getGenericSuperclass(), parameterGenericClass);
            if(result == null) {
                for (Type genericInterface : concreteChildClass.getGenericInterfaces()) {
                    if(null != (result = findFunction.apply(genericInterface, parameterGenericClass))){
                        break;
                    }
                }
            }
            return result == null? parameterGenericClass : result;
        }

        public Object inject(Object target,Class beanClass) throws IllegalStateException{
            if(this.member instanceof Field) {
                Field field = (Field) this.member;
                if (Modifier.isFinal(field.getModifiers())) {
                    return null;
                }
                Object[] values = getInjectValues(beanClass);
                try {
                    boolean accessible = field.isAccessible();
                    try {
                        field.setAccessible(true);
                        field.set(target, values[0]);
                    } finally {
                        field.setAccessible(accessible);
                    }
                } catch (Throwable e) {
                    throw new IllegalStateException("inject error=" + e + ". class=" + target.getClass() + ",field=" + this.member);
                }
            }else if(this.member instanceof Method) {
                Method method = (Method) this.member;
                Object[] values = getInjectValues(beanClass);
                try {
                    boolean accessible = method.isAccessible();
                    try {
                        method.setAccessible(true);
                        return method.invoke(target, values);
                    } finally {
                        method.setAccessible(accessible);
                    }
                } catch (Throwable e) {
                    throw new IllegalStateException("inject error=" + e + ". class=" + target.getClass() + ",method=" + this.member);
                }
            }else if(this.member instanceof Constructor){
                return newInstance(null);
            }
            return null;
        }

        public Object newInstance(Object[] args) throws IllegalStateException{
            if (this.member.getDeclaringClass().isEnum()){
                return null;
            }
            if(!(this.member instanceof Constructor)){
                throw new IllegalStateException("member not instanceof Constructor!");
            }
            Constructor constructor = (Constructor) this.member;
            if(args == null|| constructor.getParameterCount() != args.length) {
                args = getInjectValues(member.getDeclaringClass());
            }
            boolean accessible = constructor.isAccessible();
            try {
                constructor.setAccessible(true);
                Object instance = constructor.newInstance(args);
                return instance;
            } catch (IllegalAccessException | InstantiationException |
                    InvocationTargetException | IllegalArgumentException |
                    ExceptionInInitializerError e) {
                throw new IllegalStateException("inject error=" + e + ". method=" + this.member,e);
            } finally {
                constructor.setAccessible(accessible);
            }
        }

        private static Type findAnnotationDeclaredType(Annotation annotation, Type def){
            if(annotation == null) {
                return def;
            }
            Type annotationDeclaredType = getAnnotationValue(annotation,"type",Type.class);
            if(annotationDeclaredType != null && annotationDeclaredType != Object.class){
                return annotationDeclaredType;
            }else {
                return def;
            }
        }
    }

    private static class DefaultBeanNameGenerator implements Function<BeanDefinition,String>{
        private final ApplicationX applicationX;
        private final Map<Class,Boolean> scannerAnnotationsCacheMap = newConcurrentReferenceMap(32);
        public DefaultBeanNameGenerator(ApplicationX applicationX) {
            this.applicationX = Objects.requireNonNull(applicationX);
        }
        @Override
        public String apply(BeanDefinition definition) {
            Class beanClass = definition.getBeanClassIfResolve(applicationX.resourceLoader);
            Annotation annotation = findDeclaredAnnotation(beanClass, applicationX.scannerAnnotations,scannerAnnotationsCacheMap);
            String beanName = null;
            if(annotation != null){
                beanName = getAnnotationValue(annotation,"value",String.class);
            }
            if(beanName == null || beanName.isEmpty()) {
                String className = beanClass.getName();
                int lastDotIndex = className.lastIndexOf('.');
                int nameEndIndex = className.indexOf("$$");
                if (nameEndIndex == -1) {
                    nameEndIndex = className.length();
                }
                String shortName = className.substring(lastDotIndex + 1, nameEndIndex);
                shortName = shortName.replace('$', '.');
                beanName = Introspector.decapitalize(shortName);
            }
            return beanName;
        }
    }

    private class DefaultBeanFactory implements AbstractBeanFactory {
        /** Cache of filtered PropertyDescriptors: bean Class to PropertyDescriptor array. */
        private final Map<Class<?>, PropertyDescriptor[]> filteredPropertyDescriptorsCache = new ConcurrentHashMap<>();
        //如果构造参数注入缺少参数, 是否抛出异常
        private boolean defaultInjectRequiredConstructor = true;
        @Override
        public Object createBean(String beanName,BeanDefinition definition,Object[] args) {
            // Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
            Object bean = resolveBeforeInstantiation(beanName, definition);
            if (bean != null) {
                return bean;
            }
            BeanWrapper beanInstanceWrapper = createBeanInstance(beanName, definition, args);
            Object exposedObject = beanInstanceWrapper.getWrappedInstance();
            if(isLifecycle(beanName)){
                populateBean(beanName,definition,beanInstanceWrapper);
                exposedObject = initializeBean(beanName, beanInstanceWrapper, definition);
            }
            return exposedObject;
        }

        protected Object resolveBeforeInstantiation(String beanName, BeanDefinition mbd) {
            Object bean = null;
            if (!Boolean.FALSE.equals(mbd.beforeInstantiationResolved)) {
                // Make sure bean class is actually resolved at this point.
                Class<?> targetType = resolveBeanClass(beanName, mbd,resourceLoader);
                if (targetType != null) {
                    bean = applyBeanPostProcessorsBeforeInstantiation(targetType, beanName);
                    if (bean != null) {
                        bean = applyBeanPostProcessorsAfterInitialization(bean, beanName);
                    }
                }
            }
            return bean;
        }

        protected Object applyBeanPostProcessorsBeforeInstantiation(Class<?> beanClass, String beanName) {
            for (BeanPostProcessor bp : new ArrayList<>(beanPostProcessors)) {
                if (bp instanceof InstantiationAwareBeanPostProcessor) {
                    InstantiationAwareBeanPostProcessor ibp = (InstantiationAwareBeanPostProcessor) bp;
                    Object result = ibp.postProcessBeforeInstantiation(beanClass, beanName);
                    if (result != null) {
                        return result;
                    }
                }
            }
            return null;
        }

        protected Class resolveBeanClass(String beanName,BeanDefinition definition, Supplier<ClassLoader> loaderSupplier){
            return definition.getBeanClassIfResolve(loaderSupplier);
        }

        protected BeanWrapper createBeanInstance(String beanName,BeanDefinition definition,Object[] args){
            Supplier<?> beanSupplier = definition.getBeanSupplier();
            Object beanInstance;
            if(beanSupplier != null){
                beanInstance = beanSupplier.get();
            }else {
                Class<?> beanClass = resolveBeanClass(beanName,definition,resourceLoader);
                Constructor<?>[] ctors = determineConstructorsFromBeanPostProcessors(beanClass, beanName);
                if (ctors != null
                        || definition.getAutowireMode() == BeanDefinition.AUTOWIRE_CONSTRUCTOR
                        || definition.getConstructorArgumentValues().size() > 0
                        || (args != null && args.length > 0)) {
                    return autowireConstructor(beanName, definition, ctors, args);
                }
                beanInstance = newInstance(beanClass);
            }
            BeanWrapper bw = new BeanWrapper(beanInstance);
            initBeanWrapper(bw);
            return bw;
        }

        protected BeanWrapper autowireConstructor(
                String beanName, BeanDefinition mbd, Constructor<?>[] ctors, Object[] explicitArgs) throws IllegalStateException{
            for (Constructor<?> constructor : ctors) {
                InjectElement<Constructor<?>> element = new InjectElement<>(constructor, ApplicationX.this);
                try {
                    if(element.required == null){
                        element.required = defaultInjectRequiredConstructor;
                    }
                    Object beanInstance = element.newInstance(explicitArgs);
                    if(beanInstance != null) {
                        BeanWrapper bw = new BeanWrapper(beanInstance);
                        initBeanWrapper(bw);
                        return bw;
                    }
                }catch (IllegalStateException e){
                    //skip
                }
            }
            throw new IllegalStateException("can not create instances. "+Arrays.toString(ctors));
        }

        protected Constructor<?>[] determineConstructorsFromBeanPostProcessors(Class<?> beanClass, String beanName)
                throws RuntimeException {
            for (BeanPostProcessor bp : new ArrayList<>(beanPostProcessors)) {
                if (bp instanceof SmartInstantiationAwareBeanPostProcessor) {
                    SmartInstantiationAwareBeanPostProcessor ibp = (SmartInstantiationAwareBeanPostProcessor) bp;
                    Constructor<?>[] ctors = ibp.determineCandidateConstructors(beanClass, beanName);
                    if (ctors != null) {
                        return ctors;
                    }
                }
            }
            return null;
        }

        protected void initBeanWrapper(BeanWrapper bw) {
            bw.conversionService = new ConversionService(){};
//            registerCustomEditors(bw);
            //实现需参照 org.springframework.beans.factory.support.AbstractBeanFactory.registerCustomEditors
        }

        protected void populateBean(String beanName,BeanDefinition definition,BeanWrapper bw){
            boolean continueWithPropertyPopulation = true;
            for (BeanPostProcessor bp : new ArrayList<>(beanPostProcessors)) {
                if(bp instanceof InstantiationAwareBeanPostProcessor){
                    InstantiationAwareBeanPostProcessor ibp = (InstantiationAwareBeanPostProcessor) bp;
                    if (!ibp.postProcessAfterInstantiation(bw.getWrappedInstance(), beanName)) {
                        continueWithPropertyPopulation = false;
                        break;
                    }
                }
            }
            if (!continueWithPropertyPopulation) {
                return;
            }

            PropertyValues pvs = definition.getPropertyValues();
            if(definition.getAutowireMode() == BeanDefinition.AUTOWIRE_BY_NAME
                    || definition.getAutowireMode() == BeanDefinition.AUTOWIRE_BY_TYPE) {
                PropertyValues newPvs = new PropertyValues(pvs.getPropertyValues());
                if (definition.getAutowireMode() == BeanDefinition.AUTOWIRE_BY_NAME) {
                    autowireByName(beanName, definition, bw, newPvs);
                }
                if (definition.getAutowireMode() == BeanDefinition.AUTOWIRE_BY_TYPE) {
                    autowireByType(beanName, definition, bw, newPvs);
                }
                pvs = newPvs;
            }

            boolean needsDepCheck = definition.getDependencyCheck() != BeanDefinition.DEPENDENCY_CHECK_NONE;
            PropertyDescriptor[] filteredPds = null;
            for (BeanPostProcessor bp : new ArrayList<>(beanPostProcessors)) {
                if (bp instanceof InstantiationAwareBeanPostProcessor) {
                    InstantiationAwareBeanPostProcessor ibp = (InstantiationAwareBeanPostProcessor) bp;
                    PropertyValues pvsToUse = ibp.postProcessProperties(pvs, bw.getWrappedInstance(), beanName);
                    if (pvsToUse == null) {
                        if (filteredPds == null) {
                            filteredPds = filterPropertyDescriptorsForDependencyCheck(bw, definition.allowCaching);
                        }
                        pvsToUse = ibp.postProcessPropertyValues(pvs, filteredPds, bw.getWrappedInstance(), beanName);
                        if (pvsToUse == null) {
                            return;
                        }
                    }
                    pvs = pvsToUse;
                }
            }
            if (needsDepCheck) {
                if (filteredPds == null) {
                    filteredPds = filterPropertyDescriptorsForDependencyCheck(bw, definition.allowCaching);
                }
                checkDependencies(beanName, definition, filteredPds, pvs);
            }

            if (pvs != null) {
                applyPropertyValues(beanName, definition, bw, pvs);
            }
        }

        protected void checkDependencies(String beanName, BeanDefinition mbd, PropertyDescriptor[] pds, PropertyValues pvs)
                throws IllegalStateException {
            int dependencyCheck = mbd.getDependencyCheck();
            for (PropertyDescriptor pd : pds) {
                if (pd.getWriteMethod() != null && (pvs == null || !pvs.contains(pd.getName()))) {
                    boolean isSimple = isSimpleProperty(pd.getPropertyType());
                    boolean unsatisfied = (dependencyCheck == BeanDefinition.DEPENDENCY_CHECK_ALL) ||
                            (isSimple && dependencyCheck == BeanDefinition.DEPENDENCY_CHECK_SIMPLE) ||
                            (!isSimple && dependencyCheck == BeanDefinition.DEPENDENCY_CHECK_OBJECTS);
                    if (unsatisfied) {
                        throw new IllegalStateException("Set this property value or disable dependency checking for this bean.");
                    }
                }
            }
        }

        protected PropertyDescriptor[] filterPropertyDescriptorsForDependencyCheck(BeanWrapper bw, boolean cache) {
            PropertyDescriptor[] filtered = this.filteredPropertyDescriptorsCache.get(bw.getWrappedClass());
            if (filtered == null) {
                filtered = bw.getPropertyDescriptors();
                if (cache) {
                    PropertyDescriptor[] existing =
                            this.filteredPropertyDescriptorsCache.putIfAbsent(bw.getWrappedClass(), filtered);
                    if (existing != null) {
                        filtered = existing;
                    }
                }
            }
            return filtered;
        }

        public Object applyBeanPostProcessorsAfterInitialization(Object existingBean, String beanName)
                throws RuntimeException {
            Object result = existingBean;
            for (BeanPostProcessor processor : new ArrayList<>(beanPostProcessors)) {
                Object current = processor.postProcessAfterInitialization(result, beanName);
                if (current == null) {
                    return result;
                }
                result = current;
            }
            return result;
        }

        protected void applyPropertyValues(String beanName, BeanDefinition definition, BeanWrapper bw, PropertyValues pvs) {
            bw.setPropertyValues(pvs);
        }

        private <T> T newInstance(Class<T> clazz) throws IllegalStateException{
            try {
                Object instance = clazz.getDeclaredConstructor().newInstance();
                return (T) instance;
            }catch (Exception e){
                throw new IllegalStateException("newInstanceByJdk error="+e,e);
            }
        }

        private void autowireByType(String beanName,BeanDefinition definition,BeanWrapper beanInstanceWrapper,PropertyValues pvs){

        }

        private void autowireByName(String beanName,BeanDefinition definition,BeanWrapper beanInstanceWrapper,PropertyValues pvs){

        }
    }

    private static <T>T getAnnotationValue(Annotation annotation,String fieldName,Class<T> type){
        try {
            Method method = annotation.annotationType().getDeclaredMethod(fieldName);
            Object value = method.invoke(annotation);
            if(value != null && type.isAssignableFrom(value.getClass())){
                return (T) value;
            }else {
                return null;
            }
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            return null;
        }
    }

    /**
     * 寻找注解
     * @param element AnnotatedElement
     * @param finds finds annotationList
     * @param cacheMap cacheMap
     * @return Annotation
     */
    private static Annotation findDeclaredAnnotation(AnnotatedElement element, Collection<Class<? extends Annotation>> finds, Map<Class,Boolean> cacheMap){
        Annotation[] fieldAnnotations = element.getDeclaredAnnotations();
        for (Annotation annotation : fieldAnnotations) {
            boolean existAnnotation = isExistAnnotation(annotation.annotationType(), finds,cacheMap);
            if(existAnnotation){
                return annotation;
            }
        }
        return null;
    }

    private static Annotation findAnnotation(Class rootClass, Collection<Class<? extends Annotation>> finds){
        if(rootClass == null){
            return null;
        }
        Annotation result;
        //类上找
        for (Class clazz = rootClass; clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (Class<? extends Annotation> find : finds) {
                if(null != (result = clazz.getAnnotation(find))) {
                    return result;
                }
            }
        }
        //接口上找
        Collection<Class> interfaces = getInterfaces(rootClass);
        for(Class i : interfaces){
            for (Class clazz = i; clazz != null; clazz = clazz.getSuperclass()) {
                for (Class<? extends Annotation> find : finds) {
                    if (null != (result = clazz.getAnnotation(find))) {
                        return result;
                    }
                }
            }
        }
        return null;
    }

    private static Collection<Class> getInterfaces(Class sourceClass){
        Set<Class> interfaceList = new LinkedHashSet<>();
        if(sourceClass.isInterface()){
            interfaceList.add(sourceClass);
        }
        for(Class currClass = sourceClass; currClass != null && currClass != Object.class; currClass = currClass.getSuperclass()){
            Collections.addAll(interfaceList,currClass.getInterfaces());
        }
        return interfaceList;
    }

    private static boolean isSimpleProperty(Class<?> clazz) {
        return isSimpleValueType(clazz) || (clazz.isArray() && isSimpleValueType(clazz.getComponentType()));
    }

    private static boolean isSimpleValueType(Class<?> clazz) {
        return (clazz.isPrimitive() ||
                clazz == Character.class ||
                Enum.class.isAssignableFrom(clazz) ||
                CharSequence.class.isAssignableFrom(clazz) ||
                Number.class.isAssignableFrom(clazz) ||
                Date.class.isAssignableFrom(clazz) ||
                URI.class == clazz || URL.class == clazz ||
                Locale.class == clazz || Class.class == clazz);
    }

    public static class BeanDefinition {
        public static final String SCOPE_SINGLETON = "singleton";
        public static final String SCOPE_PROTOTYPE = "prototype";
        public static final int AUTOWIRE_NO = 0;
        public static final int AUTOWIRE_BY_NAME = 1;
        public static final int AUTOWIRE_BY_TYPE = 2;
        public static final int AUTOWIRE_CONSTRUCTOR = 3;
        public static final int DEPENDENCY_CHECK_NONE = 0;
        public static final int DEPENDENCY_CHECK_OBJECTS = 1;
        public static final int DEPENDENCY_CHECK_SIMPLE = 2;
        public static final int DEPENDENCY_CHECK_ALL = 3;
        final Object postProcessingLock = new Object();

        private int dependencyCheck = DEPENDENCY_CHECK_NONE;
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private Supplier<?> beanSupplier;
        private Object beanClass;
        private String beanClassName;
        private String scope = SCOPE_SINGLETON;
        private boolean primary = false;
        private boolean lazyInit = false;
        private String initMethodName;
        private String destroyMethodName;
        private int autowireMode = AUTOWIRE_NO;
        private PropertyValues propertyValues = PropertyValues.EMPTY;
        private boolean allowCaching = true;
        //用于aop等代理对象
        private volatile Boolean beforeInstantiationResolved;
        private final Map<Integer, ValueHolder> constructorArgumentValues = new LinkedHashMap<>();
        public BeanDefinition() {}

        public Map<Integer, ValueHolder> getConstructorArgumentValues() {
            return constructorArgumentValues;
        }
        public String getDestroyMethodName() {
            return destroyMethodName;
        }
        public void setDestroyMethodName(String destroyMethodName) {
            this.destroyMethodName = destroyMethodName;
        }
        public int getDependencyCheck() {
            return dependencyCheck;
        }
        public void setDependencyCheck(int dependencyCheck) {
            this.dependencyCheck = dependencyCheck;
        }
        public String getInitMethodName() {
            return initMethodName;
        }
        public void setInitMethodName(String initMethodName) {
            this.initMethodName = initMethodName;
        }
        public boolean isSingleton(){
            return SCOPE_SINGLETON.equals(scope);
        }
        public boolean isPrototype(){
            return SCOPE_PROTOTYPE.equals(scope);
        }
        public boolean isLazyInit() {
            return lazyInit;
        }
        public boolean isPrimary() {
            return primary;
        }
        public String getScope() {
            return scope;
        }
        public void setPropertyValues(PropertyValues propertyValues) {
            this.propertyValues = propertyValues;
        }
        public PropertyValues getPropertyValues() {
            return this.propertyValues;
        }
        public void setBeforeInstantiationResolved(Boolean beforeInstantiationResolved) {
            this.beforeInstantiationResolved = beforeInstantiationResolved;
        }
        public Boolean getBeforeInstantiationResolved() {
            return beforeInstantiationResolved;
        }
        public Class getBeanClass() {
            if (beanClass == null) {
                throw new IllegalStateException("No bean class specified on bean definition");
            }
            if (!(beanClass instanceof Class)) {
                throw new IllegalStateException(
                        "Bean class name [" + beanClass + "] has not been resolved into an actual Class");
            }
            return (Class) beanClass;
        }
        public Class getBeanClassIfResolve(Supplier<ClassLoader> loaderSupplier){
            if(beanClass == null || !(beanClass instanceof Class)){
                beanClass = resolveBeanClass(loaderSupplier.get());
            }
            return (Class) beanClass;
        }
        public Class resolveBeanClass(ClassLoader classLoader){
            try {
                return Class.forName(beanClassName,false,classLoader);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("getBeanClass error."+e,e);
            }
        }
        public Supplier<?> getBeanSupplier() {
            return beanSupplier;
        }
        public void setAttribute(String name, Object value){
            attributes.put(name,value);
        }
        public Object removeAttribute(String name){
            return attributes.remove(name);
        }
        public Object getAttribute(String name){
            return attributes.get(name);
        }
        public void setBeanSupplier(Supplier<?> beanSupplier) {
            this.beanSupplier = beanSupplier;
        }
        public void setBeanClass(Class beanClass) {
            this.beanClass = beanClass;
        }
        public void setScope(String scope) {
            this.scope = scope;
        }
        public void setPrimary(boolean primary) {
            this.primary = primary;
        }
        public void setLazyInit(boolean lazyInit) {
            this.lazyInit = lazyInit;
        }
        public String getBeanClassName() {
            return beanClassName;
        }
        public void setBeanClassName(String beanClassName) {
            this.beanClassName = beanClassName;
        }
        public int getAutowireMode() {
            return this.autowireMode;
        }
        public void setAutowireMode(int autowireMode) {
            this.autowireMode = autowireMode;
        }
        @Override
        public String toString() {
            return scope + '{' + beanClassName +'}';
        }
    }

    public static class ValueHolder {
        private Object value;
        private String type;
        private String name;
        private Object source;
        private boolean converted = false;
        private Object convertedValue;
        public ValueHolder(Object value) {
            this.value = value;
        }
    }

    public interface AbstractBeanFactory {
        Object createBean(String beanName, BeanDefinition definition, Object[] args)throws RuntimeException;
    }

    public interface Aware {}

    public interface BeanNameAware extends Aware{
        void setBeanName(String name);
    }

    public interface ApplicationAware extends Aware{
        void setApplication(ApplicationX applicationX);
    }

    public interface InitializingBean {
        void afterPropertiesSet() throws Exception;
    }

    public interface DisposableBean {
        void destroy() throws Exception;
    }

    public interface BeanPostProcessor {
        default Object postProcessBeforeInitialization(Object bean, String beanName) throws RuntimeException {
            return bean;
        }
        default Object postProcessAfterInitialization(Object bean, String beanName) throws RuntimeException {
            return bean;
        }
    }

    public interface SmartInstantiationAwareBeanPostProcessor extends InstantiationAwareBeanPostProcessor {
        default Class<?> predictBeanType(Class<?> beanClass, String beanName) throws RuntimeException {
            return null;
        }
        default Constructor<?>[] determineCandidateConstructors(Class<?> beanClass, String beanName)
                throws RuntimeException {
            return null;
        }
    }

    public interface InstantiationAwareBeanPostProcessor extends BeanPostProcessor{
        default Object postProcessBeforeInstantiation(Class<?> beanClass, String beanName) throws RuntimeException {
            return null;
        }
        default boolean postProcessAfterInstantiation(Object bean, String beanName) throws RuntimeException {
            return true;
        }
        default PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName)throws RuntimeException {
            return pvs;
        }
        default PropertyValues postProcessPropertyValues(
                PropertyValues pvs, PropertyDescriptor[] pds, Object bean, String beanName) throws RuntimeException {
            return pvs;
        }
    }

    public interface ConversionService{
        default boolean canConvert(Class<?> sourceType, Class<?> targetType){
            return true;
        }
        default <T> T convert(Object source, Class<T> targetType){
            return (T) source;
        }
    }

    public interface PropertyEditor {
        /**
         * Set (or change) the object that is to be edited.  Primitive types such
         * as "int" must be wrapped as the corresponding object type such as
         * "java.lang.Integer".
         *
         * @param value The new target object to be edited.  Note that this
         *              object should not be modified by the PropertyEditor, rather
         *              the PropertyEditor should create a new object to hold any
         *              modified value.
         */
        void setValue(Object value);

        /**
         * Gets the property value.
         *
         * @return The value of the property.  Primitive types such as "int" will
         * be wrapped as the corresponding object type such as "java.lang.Integer".
         */

        Object getValue();
    }

    public static class PropertyValues implements Iterable<PropertyValue>{
        public static PropertyValues EMPTY = new PropertyValues(new PropertyValue[0]);
        private PropertyValue[] propertyValues;
        public PropertyValues(PropertyValue[] propertyValues) {
            this.propertyValues = propertyValues;
        }
        @Override
        public Iterator<PropertyValue> iterator() {
            return Arrays.asList(getPropertyValues()).iterator();
        }
        @Override
        public Spliterator<PropertyValue> spliterator() {
            return Spliterators.spliterator(getPropertyValues(), 0);
        }
        public Stream<PropertyValue> stream() {
            return StreamSupport.stream(spliterator(), false);
        }
        public PropertyValue[] getPropertyValues(){
            return propertyValues;
        }
        public boolean contains(String propertyName){
            for (PropertyValue value : propertyValues) {
                if(Objects.equals(propertyName,value.name)){
                    return true;
                }
            }
            return false;
        }
        public boolean isEmpty(){
            return propertyValues.length == 0;
        }
    }

    public static class PropertyValue{
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private Object source;
        private final String name;
        private final Object value;
        private boolean optional = false;
        private boolean converted = false;
        private Object convertedValue;
        public PropertyValue(String name, Object value) {
            this.name = name;
            this.value = value;
        }
    }

    public static class OrderComparator implements Comparator<Object>{
        private final Collection<Class<? extends Annotation>> orderedAnnotations;
        public OrderComparator(Collection<Class<? extends Annotation>> orderedAnnotations) {
            this.orderedAnnotations = Objects.requireNonNull(orderedAnnotations);
        }
        @Override
        public int compare(Object o1, Object o2) {
            int c1 = convertInt(o1);
            int c2 = convertInt(o2);
            return c1 < c2 ? -1 : 1;
        }
        protected int convertInt(Object o){
            Annotation annotation;
            int order;
            if(o == null){
                order = Integer.MAX_VALUE;
            }else if (o instanceof Ordered){
                order = ((Ordered) o).getOrder();
            }else if ((annotation = findAnnotation(o.getClass(), orderedAnnotations)) != null){
                Number value = getAnnotationValue(annotation, "value", Number.class);
                if (value != null) {
                    order = value.intValue();
                } else {
                    order = Integer.MAX_VALUE;
                }
            }else {
                order = Integer.MAX_VALUE;
            }
            return order;
        }
    }

    @Order(Integer.MIN_VALUE + 10)
    public static class RegisteredBeanPostProcessor implements BeanPostProcessor{
        private final ApplicationX applicationX;
        public RegisteredBeanPostProcessor(ApplicationX applicationX) {
            this.applicationX = Objects.requireNonNull(applicationX);
        }
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) throws RuntimeException {
            if(bean instanceof BeanPostProcessor){
                applicationX.addBeanPostProcessor((BeanPostProcessor) bean);
            }
            return bean;
        }
    }

    @Order(Integer.MIN_VALUE + 20)
    public static class AutowiredConstructorPostProcessor implements SmartInstantiationAwareBeanPostProcessor{
        private static final Constructor[] EMPTY = {};
        //如果字段参数注入缺少参数, 是否抛出异常
        private boolean defaultInjectRequiredField = true;
        //如果方法参数注入缺少参数, 是否抛出异常
        private boolean defaultInjectRequiredMethod = true;
        private final ApplicationX applicationX;
        public AutowiredConstructorPostProcessor(ApplicationX applicationX) {
            this.applicationX = applicationX;
        }
        @Override
        public Constructor<?>[] determineCandidateConstructors(Class<?> beanClass, String beanName) throws RuntimeException {
            List<Constructor<?>> list = new LinkedList<>();
            Constructor<?>[] constructors = beanClass.getDeclaredConstructors();
            for (Constructor<?> constructor : constructors) {
                if(constructor.isSynthetic()){
                    return null;
                }
                if(constructor.getParameterCount() == 0 && Modifier.isPublic(constructor.getModifiers())){
                    return null;
                }
            }
            for (Constructor<?> constructor : constructors) {
                if(Modifier.isPublic(constructor.getModifiers())) {
                    list.add(constructor);
                }
            }
            if(list.isEmpty()){
                throw new IllegalStateException("No visible constructors in "+beanName);
            }
            return list.size() == constructors.length? constructors : list.toArray(EMPTY);
        }

        @Override
        public boolean postProcessAfterInstantiation(Object bean, String beanName) throws RuntimeException {
            BeanDefinition definition = applicationX.getBeanDefinition(beanName);
            Class beanClass = definition.getBeanClassIfResolve(applicationX.getResourceLoader());
            List<InjectElement<Field>> declaredFields = InjectElement.getInjectFields(beanClass,applicationX);
            List<InjectElement<Method>> declaredMethods = InjectElement.getInjectMethods(beanClass,applicationX);
            for (InjectElement<Field> element : declaredFields) {
                if(element.required == null){
                    element.required = defaultInjectRequiredField;
                }
                element.inject(bean,beanClass);
            }
            for (InjectElement<Method> element : declaredMethods) {
                if(element.required == null){
                    element.required = defaultInjectRequiredMethod;
                }
                element.inject(bean,beanClass);
            }
            return true;
        }
    }

    @Target(TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    public @interface Component {
        String value() default "";
    }

    @Target({CONSTRUCTOR, METHOD, PARAMETER, FIELD, ANNOTATION_TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    public @interface Autowired {
        boolean required() default true;
    }

    // TODO: 1月27日 027 @Value not impl config
    @Target({CONSTRUCTOR, METHOD, PARAMETER, FIELD, ANNOTATION_TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    public @interface Value {
        String value() default "";
    }

    @Target({TYPE, METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    public @interface Primary {}

    @Target({TYPE, FIELD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Resource {
        String value() default "";
        Class<?> type() default Object.class;
    }

    @Documented
    @Retention (RetentionPolicy.RUNTIME)
    @Target(METHOD)
    public @interface PostConstruct {}

    @Documented
    @Retention (RetentionPolicy.RUNTIME)
    @Target(METHOD)
    public @interface PreDestroy {}

    @Target({TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Scope {
        String value() default BeanDefinition.SCOPE_SINGLETON;
    }

    @Target({TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Lazy {
        boolean value() default true;
    }

    @Target({FIELD, METHOD, PARAMETER, TYPE, ANNOTATION_TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    @Inherited
    @Documented
    public @interface Qualifier {
        String value() default "";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
    @Documented
    public @interface Order {
        /**
         * 从小到大排列
         * @return 排序
         */
        int value() default Integer.MAX_VALUE;
    }

    public interface Ordered {
        /**
         * 从小到大排列
         * @return 排序
         */
        int getOrder();
    }

    public static class BeanWrapper {
        /**
         * Path separator for nested properties.
         * Follows normal Java conventions: getFoo().getBar() would be "foo.bar".
         */
        public static final String NESTED_PROPERTY_SEPARATOR = ".";

        /**
         * Path separator for nested properties.
         * Follows normal Java conventions: getFoo().getBar() would be "foo.bar".
         */
        public static final char NESTED_PROPERTY_SEPARATOR_CHAR = '.';

        /**
         * Marker that indicates the start of a property key for an
         * indexed or mapped property like "person.addresses[0]".
         */
        public static final String PROPERTY_KEY_PREFIX = "[";

        /**
         * Marker that indicates the start of a property key for an
         * indexed or mapped property like "person.addresses[0]".
         */
        public static final char PROPERTY_KEY_PREFIX_CHAR = '[';

        /**
         * Marker that indicates the end of a property key for an
         * indexed or mapped property like "person.addresses[0]".
         */
        public static final String PROPERTY_KEY_SUFFIX = "]";

        /**
         * Marker that indicates the end of a property key for an
         * indexed or mapped property like "person.addresses[0]".
         */
        public static final char PROPERTY_KEY_SUFFIX_CHAR = ']';
        //by org.springframework.beans.ConfigurablePropertyAccessor
        private ConversionService conversionService;
        private Object wrappedInstance;
        private Class<?> wrappedClass;
        private PropertyDescriptor[] cachedIntrospectionResults;

        public BeanWrapper(Object wrappedInstance) {
            this.wrappedInstance = wrappedInstance;
            this.wrappedClass = wrappedInstance.getClass();
        }

        public PropertyDescriptor[] getPropertyDescriptors() {
            if(cachedIntrospectionResults == null){
                cachedIntrospectionResults = getPropertyDescriptorsIfCache(wrappedClass);
            }
            return cachedIntrospectionResults;
        }

        public Class<?> getWrappedClass() {
            return wrappedClass;
        }

        public Object getWrappedInstance() {
            return wrappedInstance;
        }

        public boolean isReadableProperty(String propertyName){
            PropertyDescriptor descriptor = getPropertyDescriptor(propertyName);
            if(descriptor == null){
                return false;
            }
            return descriptor.getReadMethod() != null;
        }

        public boolean isWritableProperty(String propertyName){
            PropertyDescriptor descriptor = getPropertyDescriptor(propertyName);
            if(descriptor == null){
                return false;
            }
            return descriptor.getWriteMethod() != null;
        }

        public Class<?> getPropertyType(String propertyName) throws IllegalArgumentException,IllegalStateException{
            PropertyDescriptor descriptor = getPropertyDescriptor(propertyName);
            if(descriptor == null){
                throw new IllegalArgumentException("No property handler found");
            }
            return descriptor.getPropertyType();
        }

        public Type getPropertyTypeDescriptor(String propertyName) throws IllegalArgumentException,IllegalStateException{
            return getPropertyType(propertyName);
        }

        public Object getPropertyValue(String propertyName) throws IllegalArgumentException,IllegalStateException{
            PropertyDescriptor descriptor = getPropertyDescriptor(propertyName);
            if(descriptor == null){
                throw new IllegalArgumentException("No property handler found");
            }
            Method readMethod = descriptor.getReadMethod();
            if(readMethod == null){
                throw new IllegalStateException("Not readable. name="+propertyName);
            }
            try {
                return readMethod.invoke(wrappedInstance);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("readMethod error. name="+propertyName,e);
            }
        }

        public void setPropertyValue(String propertyName, Object value) throws IllegalArgumentException,IllegalStateException{
            PropertyDescriptor descriptor = getPropertyDescriptor(propertyName);
            if(descriptor == null){
                throw new IllegalArgumentException("No property handler found");
            }
            Object convertedResult = convertIfNecessary(value, descriptor.getPropertyType());
            Method writeMethod = descriptor.getWriteMethod();
            if(writeMethod == null){
                throw new IllegalStateException("Not writable. name="+propertyName);
            }
            try {
                writeMethod.invoke(wrappedInstance,convertedResult);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("writeMethod error. name="+propertyName,e);
            }
        }

        public void setPropertyValue(PropertyValue pv) throws IllegalArgumentException,IllegalStateException{
            // TODO: 1月26日 026 setPropertyValue
            setPropertyValue(pv.name,pv.value);
        }

        public void setPropertyValues(Map<?, ?> map) throws IllegalArgumentException,IllegalStateException{
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                setPropertyValue(entry.getKey().toString(),entry.getValue());
            }
        }

        public void setPropertyValues(PropertyValues pvs) throws IllegalArgumentException,IllegalStateException{
            setPropertyValues(pvs,false, false);
        }

        public void setPropertyValues(PropertyValues pvs, boolean ignoreUnknown)
                throws IllegalArgumentException,IllegalStateException{
            setPropertyValues(pvs,ignoreUnknown, false);
        }

        public void setPropertyValues(PropertyValues pvs, boolean ignoreUnknown, boolean ignoreInvalid)
                throws IllegalArgumentException,IllegalStateException{
            for (PropertyValue pv : pvs) {
                try {
                    setPropertyValue(pv);
                }catch (IllegalArgumentException e){
                    if (!ignoreUnknown) {
                        throw e;
                    }
                }catch (IllegalStateException e){
                    if (!ignoreInvalid) {
                        throw e;
                    }
                }
            }
        }

        public PropertyDescriptor getPropertyDescriptor(String propertyName) throws IllegalArgumentException{
            for (PropertyDescriptor descriptor : getPropertyDescriptors()) {
                if(descriptor.getName().equals(propertyName)){
                    return descriptor;
                }
            }
            return null;
        }
        //by org.springframework.beans.TypeConverter
        public <T> T convertIfNecessary(Object value, Class<T> requiredType) throws IllegalArgumentException{
            Class<?> sourceType = value != null? value.getClass(): null;
            Class<?> targetType = requiredType;
            Object convertValue = value;
            if(conversionService.canConvert(sourceType,targetType)){
                convertValue = conversionService.convert(value,targetType);
            }
            return (T) convertValue;
        }
    }

    private void shutdownHook(){
        for (Map.Entry<String, BeanDefinition> entry : beanDefinitionMap.entrySet()) {
            String beanName = entry.getKey();
            BeanDefinition definition = entry.getValue();
            try {
                Object bean = getBean(beanName, null, false);
                if(bean == null){
                    continue;
                }
                invokeBeanDestroy(beanName,bean,definition);
            }catch (RuntimeException e){
                //skip
            }
        }
    }

    private void invokeBeanDestroy(String beanName, Object bean,BeanDefinition definition) throws IllegalStateException{
        boolean isDisposableBean = bean instanceof DisposableBean;
        if(isDisposableBean){
            try {
                ((DisposableBean)bean).destroy();
            } catch (Exception e) {
                throw new IllegalStateException("invokeBeanDestroy destroy beanName="+beanName+".error="+e,e);
            }
        }
        String destroyMethodName = definition.getDestroyMethodName();
        if (destroyMethodName != null && destroyMethodName.length() > 0 &&
                !(isDisposableBean && "destroy".equals(destroyMethodName))) {
            Class<?> beanClass = definition.getBeanClassIfResolve(resourceLoader);
            try {
                beanClass.getMethod(destroyMethodName).invoke(bean);
            } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                throw new IllegalStateException("invokeBeanDestroy destroyMethodName beanName="+beanName+",destroyMethodName"+destroyMethodName+",error="+e,e);
            }
        }
    }

    private void invokeBeanInitialization(String beanName, Object bean, BeanDefinition definition) throws IllegalStateException{
        boolean isInitializingBean = bean instanceof InitializingBean;
        if(isInitializingBean){
            try {
                ((InitializingBean)bean).afterPropertiesSet();
            } catch (Exception e) {
                throw new IllegalStateException("invokeBeanInitialization afterPropertiesSet beanName="+beanName+".error="+e,e);
            }
        }
        String initMethodName = definition.getInitMethodName();
        if (initMethodName != null && initMethodName.length() > 0 &&
                !(isInitializingBean && "afterPropertiesSet".equals(initMethodName))) {
            Class<?> beanClass = definition.getBeanClassIfResolve(resourceLoader);
            try {
                beanClass.getMethod(initMethodName).invoke(bean);
            } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                throw new IllegalStateException("invokeBeanInitialization initMethodName beanName="+beanName+",initMethodName"+initMethodName+",error="+e,e);
            }
        }
    }

    public String[] getBeanNamesForType(){
        return beanDefinitionMap.keySet().toArray(new String[0]);
    }

    public Collection<Class<? extends Annotation>> getInitMethodAnnotations() {
        return initMethodAnnotations;
    }

    public Collection<Class<? extends Annotation>> getScannerAnnotations() {
        return scannerAnnotations;
    }

    public Collection<Class<? extends Annotation>> getAutowiredAnnotations() {
        return autowiredAnnotations;
    }

    public Collection<Class<? extends Annotation>> getQualifierAnnotations() {
        return qualifierAnnotations;
    }

    public Collection<Class<? extends Annotation>> getDestroyMethodAnnotations() {
        return destroyMethodAnnotations;
    }

    public Collection<Class<? extends Annotation>> getOrderedAnnotations() {
        return orderedAnnotations;
    }

    public Collection<BeanPostProcessor> getBeanPostProcessors() {
        return beanPostProcessors;
    }

    public Collection<String> getBeanSkipLifecycles() {
        return beanSkipLifecycles;
    }

    public Function<BeanDefinition, String> getBeanNameGenerator() {
        return beanNameGenerator;
    }

    public void setBeanNameGenerator(Function<BeanDefinition, String> beanNameGenerator) {
        this.beanNameGenerator = beanNameGenerator;
    }

    public boolean isLifecycle(String beanName){
        return !beanSkipLifecycles.contains(beanName);
    }

    public Supplier<ClassLoader> getResourceLoader() {
        return resourceLoader;
    }

    public void setResourceLoader(Supplier<ClassLoader> resourceLoader) {
        this.resourceLoader = Objects.requireNonNull(resourceLoader);
    }

    public Collection<String> getRootPackageList(){
        return scanner.getRootPackages();
    }

    /*==============static-utils=============================*/

    private static String findMethodNameByNoArgs(Class clazz, Collection<Class<? extends Annotation>> methodAnnotations){
        for (Method method : clazz.getMethods()) {
            if(method.getDeclaringClass() == Object.class
                    || method.getReturnType() != void.class
                    || method.getParameterCount() != 0){
                continue;
            }
            for (Class<? extends Annotation> aClass : methodAnnotations) {
                if(method.getAnnotationsByType(aClass).length == 0) {
                    continue;
                }
                if(method.getParameterCount() != 0){
                    throw new IllegalStateException("method does not have parameters. class="+clazz+",method="+method);
                }
                return method.getName();
            }
        }
        return null;
    }

    private static PropertyDescriptor[] getPropertyDescriptorsIfCache(Class clazz) throws IllegalStateException{
        PropertyDescriptor[] result = PROPERTY_DESCRIPTOR_CACHE_MAP.get(clazz);
        if(result == null) {
            try {
                BeanInfo beanInfo = Introspector.getBeanInfo(clazz, Object.class, Introspector.USE_ALL_BEANINFO);
                PropertyDescriptor[] descriptors = beanInfo.getPropertyDescriptors();
                if(descriptors != null){
                    result = descriptors;
                }else {
                    result = EMPTY_DESCRIPTOR_ARRAY;
                }
                PROPERTY_DESCRIPTOR_CACHE_MAP.put(clazz,result);
            } catch (IntrospectionException e) {
                throw new IllegalStateException("getPropertyDescriptors error. class=" + clazz+e,e);
            }
            // TODO: 1月28日 028 getPropertyDescriptorsIfCache
            // skip GenericTypeAwarePropertyDescriptor leniently resolves a set* write method
            // against a declared read method, so we prefer read method descriptors here.
        }
        return result;
    }

    private static <K,V>ConcurrentMap<K,V> newConcurrentReferenceMap(int initialCapacity){
        if(CONCURRENT_REFERENCE_MAP_CONSTRUCTOR != null){
            try {
                return CONCURRENT_REFERENCE_MAP_CONSTRUCTOR.newInstance(initialCapacity);
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                //skip
            }
        }
        return new ConcurrentHashMap<>(initialCapacity);
    }

    private static Method[] getDeclaredMethods(Class<?> clazz) {
        Objects.requireNonNull(clazz);
        Method[] result = DECLARED_METHODS_CACHE.get(clazz);
        if (result == null) {
            try {
                Method[] declaredMethods = clazz.getDeclaredMethods();
                List<Method> defaultMethods = findConcreteMethodsOnInterfaces(clazz);
                if (defaultMethods != null) {
                    result = new Method[declaredMethods.length + defaultMethods.size()];
                    System.arraycopy(declaredMethods, 0, result, 0, declaredMethods.length);
                    int index = declaredMethods.length;
                    for (Method defaultMethod : defaultMethods) {
                        result[index] = defaultMethod;
                        index++;
                    }
                }else {
                    result = declaredMethods;
                }
                DECLARED_METHODS_CACHE.put(clazz, (result.length == 0 ? EMPTY_METHOD_ARRAY : result));
            }
            catch (Throwable ex) {
                throw new IllegalStateException("Failed to introspect Class [" + clazz.getName() +
                        "] from ClassLoader [" + clazz.getClassLoader() + "]", ex);
            }
        }
        return result;
    }

    private static List<Method> findConcreteMethodsOnInterfaces(Class<?> clazz) {
        List<Method> result = null;
        for (Class<?> ifc : clazz.getInterfaces()) {
            for (Method ifcMethod : ifc.getMethods()) {
                if (!Modifier.isAbstract(ifcMethod.getModifiers())) {
                    if (result == null) {
                        result = new ArrayList<>();
                    }
                    result.add(ifcMethod);
                }
            }
        }
        return result;
    }

    private static void eachClass(Class<?> clazz, Consumer<Class> consumer) {
        // Keep backing up the inheritance hierarchy.
        consumer.accept(clazz);
        Class<?> superclass = clazz.getSuperclass();
        if (superclass != null && superclass != Object.class) {
            eachClass(superclass, consumer);
        }else if (clazz.isInterface()) {
            for (Class<?> superIfc : clazz.getInterfaces()) {
                eachClass(superIfc, consumer);
            }
        }
    }
    private static <T>Constructor<T> getAnyConstructor(Class<?>[] parameterTypes,
                                                       String... referenceMaps){
        for (String s : referenceMaps) {
            try {
                Class<T> aClass = (Class<T>) Class.forName(s);
                return aClass.getDeclaredConstructor(parameterTypes);
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                //skip
            }
        }
        return null;
    }
}
package com.zqr.zqrframework;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ZqrDispatchServlet extends HttpServlet {


    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        this.doPost(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            doDispatch(req,resp);
        } catch (Exception e) {
            e.fillInStackTrace();
            resp.getWriter().write("500 Exception "+ Arrays.toString(e.getStackTrace()));
        }
    }

    private void doDispatch(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        //找到对应的handler
        ZqrHandler zqrHandler = getZqrHandler(req);
        if (null == zqrHandler) {
            resp.getWriter().write("404 NOT Found!!!");
            return;
        }
        Map<String, Integer> paramIndexMapping = zqrHandler.paramIndexMapping;
        // 1.通过相对url找到method
        Method method = zqrHandler.getMethod();
        // 2.动态获取url参数
        // 找方法的形参列表
        Class<?>[] parameterTypes = method.getParameterTypes();
        //创建通过反射调用方法的参数列表，并赋值
        Object[] paramValues = new Object[parameterTypes.length];

        Map<String,String[]> params = req.getParameterMap();
        for (Map.Entry<String,String[]> entry:params.entrySet()) {
            String[] values = entry.getValue();
            String value = Arrays.toString(values).replaceAll("\\[|\\]", "").replaceAll("\\s", ",");
            if (paramIndexMapping.containsKey(entry.getKey())){
                //参数索引设置
                Integer index = paramIndexMapping.get(entry.getKey());
                paramValues[index] = convert(parameterTypes[index],value);
            }
        }
        // 3.设置request和response参数
        if (paramIndexMapping.containsKey(HttpServletRequest.class.getName())){
            Integer index = paramIndexMapping.get(HttpServletRequest.class.getName());
            paramValues[index] = req;
        }
        if (paramIndexMapping.containsKey(HttpServletResponse.class.getName())){
            Integer index = paramIndexMapping.get(HttpServletResponse.class.getName());
            paramValues[index] = resp;
        }

        //反射执行
        method.invoke(zqrHandler.getController(),paramValues);
    }
    private Object convert(Class<?> type,String value){
        if(Integer.class == type){
         return Integer.valueOf(value);
        }
        return value;
    }
    /**
     * 获取Handler
     * @param req
     * @return
     */
    private ZqrHandler getZqrHandler(HttpServletRequest req) {
        if (null == handlerMapping) return null;
        String url = req.getRequestURI();
        String contextPath = req.getContextPath();
        url = url.replace(contextPath,"").replaceAll("/+","/");
        for (ZqrHandler zqrHandler: handlerMapping) {
            Pattern pattern = zqrHandler.getPattern();
            Matcher matcher = pattern.matcher(url);
            if (!matcher.matches()) continue;
            return zqrHandler;

        }
        return null;
    }


    Map<String,Object> ioc = new HashMap<String,Object>();
    Properties configContext = new Properties();
    List<String> classNames = new ArrayList<String>();
//    Map<String,Method> handlerMapping = new HashMap<String,Method>();
    List<ZqrHandler> handlerMapping = new ArrayList<ZqrHandler>();

    @Override
    public void init(ServletConfig config) throws ServletException {

        //1.加载配置文件
        doLoadConfig(config.getInitParameter("contextConfigLocation"));

        //2.扫描配置中scanPackage下的class
        doScanner(configContext.getProperty("scanPackage"));

        //3.初始化被扫描的类,并放入IOC容器中
        doInstance();

        //4.进行DI依赖注入
        doAutowired();

        //5.初始化handlerMapping
        initHandlerMapping();

        System.out.println("ZqrSpring framework is init!");
    }

    private void initHandlerMapping() {
        if (ioc.isEmpty()) return;
        Set<Map.Entry<String, Object>> entries = ioc.entrySet();
        for (Map.Entry<String, Object> entry :entries) {
            Object value = entry.getValue();
            Class<?> valueClass = value.getClass();
            if (valueClass.isAnnotationPresent(ZqrController.class)){ //controller
                String baseUrl = "";
                if (valueClass.isAnnotationPresent(ZqrRequestMapping.class)){
                    ZqrRequestMapping requestMapping = valueClass.getAnnotation(ZqrRequestMapping.class);
                    baseUrl = requestMapping.value();
                }
                Method[] methods = valueClass.getMethods();
                for (Method method:methods) {
                    if (!method.isAnnotationPresent(ZqrRequestMapping.class)) continue;;
                    ZqrRequestMapping parameterAnnotation = method.getAnnotation(ZqrRequestMapping.class);
                    String url = "/"+baseUrl+"/"+parameterAnnotation.value().replaceAll("/+","/");
                    Pattern pattern = Pattern.compile(url);
                    ZqrHandler zqrHandler = new ZqrHandler(value,pattern,method);
                    handlerMapping.add(zqrHandler);
                    System.out.println("Mapped "+url+","+method);
                }
            }
        }
    }

    private void doAutowired() {
        if (ioc.isEmpty()) return;
        Set<Map.Entry<String, Object>> entries = ioc.entrySet();
        for (Map.Entry<String, Object> entry:entries) {
            if (null == entries) continue;
            Object value = entry.getValue();
            Class<?> valueClass = value.getClass();
            Field[] fields = valueClass.getDeclaredFields();
            for (Field field:fields) {
                if (field.isAnnotationPresent(ZqrAutowired.class)){
                    ZqrAutowired zqrAutowired = field.getAnnotation(ZqrAutowired.class);
                    String beanName = zqrAutowired.value().trim();
                    if ("".equals(beanName)){
                        beanName = field.getType().getName();
                    }
                    //强吻
                    field.setAccessible(true);
                    try {
                        //反射，动态给字段赋值
                        field.set(value,ioc.get(beanName));
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                }


            }
        }
    }

    private void doInstance() {
        if (classNames.isEmpty()) return;
        for (String className : classNames) {
            if (!className.contains(".")) continue;
            try {
                //1.获取Class类
                Class<?> clzz = Class.forName(className);
                //2.把instance放入ioc中
                if (clzz.isAnnotationPresent(ZqrController.class)) {
                    ioc.put(toLowerFirstCase(clzz.getSimpleName()), clzz.newInstance());
                } else if (clzz.isAnnotationPresent(ZqrService.class)) {
                    ZqrService service = clzz.getAnnotation(ZqrService.class);
                    String beanName = service.value();
                    if ("".equals(beanName)){
                        beanName = toLowerFirstCase(clzz.getSimpleName());
                    }
                    Object instance = clzz.newInstance();
                    ioc.put(beanName, instance);
                    //接口类型相关实例也放入iod
                    for (Class inter : clzz.getInterfaces()) {
                        if (ioc.containsKey(inter.getName())) {
                            throw new Exception("The "+inter.getName()+" is exists!");
                        }
                        ioc.put(inter.getName(), instance);
                    }
                } else continue;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private String toLowerFirstCase(String simpleName) {
        char[] chars = simpleName.toCharArray();
        chars[0] += 32;
        return String.valueOf(chars);
    }

    private void doLoadConfig(String contextConfigLocation) {
        InputStream is = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation);
        try {
            configContext.load(is);
        } catch (IOException e) {
            e.printStackTrace();
        }finally {
            try {
                is.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 把所有扫面的类名放入map中的key
     */
    private void doScanner(String scanPackage) {
        URL url = this.getClass().getClassLoader().getResource("/" + scanPackage.replaceAll("\\.", "/"));
        String urlFile = url.getFile();
        File classDir = new File(urlFile);
        for (File file:classDir.listFiles()) {
            if(file.isDirectory()){
                doScanner(scanPackage+"."+file.getName());
            }else{
                if (!file.getName().endsWith(".class")) continue;
                String clzzName = scanPackage + "." + file.getName().replace(".class", "");
                classNames.add(clzzName);
            }
        }
    }
}


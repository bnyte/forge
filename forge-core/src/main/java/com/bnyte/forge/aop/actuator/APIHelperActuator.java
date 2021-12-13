package com.bnyte.forge.aop.actuator;

import com.bnyte.forge.http.param.CurrentBody;
import com.bnyte.forge.http.param.CurrentPathVariable;
import com.bnyte.forge.annotation.APIHelper;
import com.bnyte.forge.enums.HttpSchedule;
import com.bnyte.forge.enums.LogOutputType;
import com.bnyte.forge.http.param.CurrentQueryParam;
import com.bnyte.forge.util.JacksonUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * APIHelper动态代理的执行器
 * @auther bnyte
 * @date 2021-12-04 03:08
 * @email bnytezz@gmail.com
 */
@Aspect
@Component
public class APIHelperActuator {

    private static final Logger log = LoggerFactory.getLogger(APIHelperActuator.class);

    /**
     * 本次请求的请求对象
     */
    private HttpServletRequest request;

    /**
     * 本次请求响应对象
     */
    private HttpServletResponse response;

    /**
     * 本次请求的响应结果，如果没有则为空
     */
    private Object result;

    /**
     * 本次请求代理的代理方法
     */
    private ProceedingJoinPoint point;

    /**
     * 本次请求发起时间
     */
    private long requestTime;

    /**
     * 本次请求的servlet请求对象
     */
    private ServletRequestAttributes attributes;

    /**
     * 本次请求目标执行的方法对象
     */
    private Method invokeMethod;

    /**
     * 本次请求执行的目标方法上方的@APIHelper注解标识
     */
    private APIHelper apiHelper;

    /**
     * 请求的请求头
     *  在下一个版本中将更新为map进行存储
     */
    private String headers;

    /**
     * 日志输出方式：TO_STRING，JSON
     */
    private LogOutputType logOutputType;

    /**
     * 本次请求id
     */
    private String id;

    /**
     * 路径（URI）参数
     */
    private CurrentPathVariable pathVariable = new CurrentPathVariable();

    /**
     * 查询参数
     */
    private CurrentQueryParam queryParam = new CurrentQueryParam();

    /**
     * body参数
     */
    private CurrentBody body = new CurrentBody();

    /**
     * 被执行的目标方法中的参数值（该数组中是真正有值的数组 ）
     */
    private Object[] args;

    /**
     * 切入点
     */
    @Pointcut("@annotation(com.bnyte.forge.annotation.APIHelper)")
    public void pointcut(){}

    /**
     * 环绕通知，具体的接口日志输出点，改方式当接口出现异常时不会输出异常日志
     * @param point 切入点重要参数
     * @return 返回方法执行之后的方法返回值
     */
    @Around("pointcut()")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        // 必要的一些初始化
        beforeRequiredInit(point);

        // 如果该方法并没有Http相关的请求对象则直接自行方法不输出请求和响应日志
        if (this.attributes == null) {
            log.warn("current invoke method'" + invokeMethod.getName() + "()'not have request bind, This may be an internal call.");
            return point.proceed();
        }

        // 输出日志
        outputLogger(HttpSchedule.REQUEST);

        // 执行目标方法
        setResult(point.proceed());

        // 设置响应对象并输出日志
        setResponse(attributes.getResponse());
        outputLogger(HttpSchedule.RESPONSE);
        return point.proceed();
    }

    /**
     * 构建请求日志
     */
    public String buildRequestLogger() throws IOException {
        StringBuilder logger = new StringBuilder();
        setHeaders();

        // 处理并设置参数
        handlerParameter();

        logger.append("\nRequest\n")
                .append("\tid: ").append(id).append("\n")
                .append("\tpath: ").append(URLDecoder.decode(request.getRequestURI(), StandardCharsets.UTF_8)).append("\n")
                .append("\theaders: ").append(headers).append("\n")
                .append("\ttype: ").append(request.getMethod()).append("\n")
                .append("\tname: ").append(invokeMethod.getName());
        if (!this.pathVariable.isEmpty()) {
            if (!logger.toString().endsWith("\n")) logger.append("\n");
            logger.append("\tpathVariable: ").append(JacksonUtils.toJSONString(this.pathVariable));
        }
        if (!this.queryParam.isEmpty()) {
            if (!logger.toString().endsWith("\n")) logger.append("\n");
            logger.append("\tquery: ").append(JacksonUtils.toJSONString(this.queryParam));
        }
        if (!this.body.isEmpty()) {
            if (!logger.toString().endsWith("\n")) logger.append("\n");
            logger.append("\tbody: ").append(JacksonUtils.toJSONString(this.body));
        }
        return logger.toString();
    }

    /**
     * 处理路径参数
     */
    public void handlerParameter() {

        Parameter[] parameters = invokeMethod.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            // 1. PathVariable
            PathVariable pathVariableAnnotation = parameters[i].getAnnotation(PathVariable.class);
            if (pathVariableAnnotation != null) {
                String key = pathVariableAnnotation.value();
                if (!StringUtils.hasText(key)) key = pathVariableAnnotation.name();
                pathVariable.put(key, this.args[i]);
            }
            // 2. queryParam
            RequestParam requestParamAnnotation = parameters[i].getAnnotation(RequestParam.class);
            if (requestParamAnnotation != null) {
                String key = requestParamAnnotation.value();
                String value = requestParamAnnotation.defaultValue();
                if (!StringUtils.hasText(key)) key = requestParamAnnotation.name();
                if (StringUtils.hasText(String.valueOf(this.args[i]))) value = String.valueOf(this.args[i]);
                this.queryParam.put(key, value);
            }
            // 3. body
            RequestBody requestBody = parameters[i].getAnnotation(RequestBody.class);
            if (requestBody != null) {
                this.body.add(args[i]);
            }
        }
    }

    /**
     * 构建响应日志
     */
    public String buildResponseLogger() {
        StringBuilder logger = new StringBuilder();
        logger.append("\nResponse\n")
                .append("\tid: ").append(id).append("\n")
                .append("\tstatus: ").append(response.getStatus()).append("\n")
                .append("\tbody: ").append(result).append("\n");
        if (apiHelper.executeTime()) logger.append("\ttime: ").append(System.currentTimeMillis() - requestTime);
        // 添加请求参数日志输出
        return logger.toString();
    }

    public void setRequest(HttpServletRequest request) {
        this.request = request;
    }

    public void setResponse(HttpServletResponse response) {
        this.response = response;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    public void setPoint(ProceedingJoinPoint point) {
        this.point = point;
    }

    public void setRequestTime(long requestTime) {
        this.requestTime = requestTime;
    }

    public void setAttributes(ServletRequestAttributes attributes) {
        this.attributes = attributes;
    }

    /**
     * 输出日志
     */
    public void outputLogger(HttpSchedule schedule) throws IOException {
        // 构建日志
        switch (schedule) {
            case REQUEST:
                if (apiHelper.enableRequest()) {
                    String requestLogger = buildRequestLogger();
                    log.info(requestLogger);
                }
                break;
            case RESPONSE:
                if (apiHelper.enableResponse()) {
                    String responseLogger = buildResponseLogger();
                    log.info(responseLogger);
                }
                break;
        }
    }


    /**
     * 必要的前置数据初始化
     */
    public void beforeRequiredInit(ProceedingJoinPoint point) {

        // 请求时间
        setRequestTime(System.currentTimeMillis());
        id = UUID.randomUUID().toString().replaceAll("-", "");
        setPoint(point);

        // 重置参数对象
        resetParameters();

        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        setAttributes(attributes);

        if (this.attributes != null) {
            setRequest(this.attributes.getRequest());
        }

        this.args = point.getArgs();

        MethodSignature methodSignature = (MethodSignature) point.getSignature();
        Method method = methodSignature.getMethod();
        setInvokeMethod(method);

        APIHelper apiHelper = method.getAnnotation(APIHelper.class);
        setApiHelper(apiHelper);

        setLogOutputType(apiHelper.output());
    }

    private void resetParameters() {
        this.body = new CurrentBody();
        this.queryParam = new CurrentQueryParam();
        this.pathVariable = new CurrentPathVariable();
    }

    public void setInvokeMethod(Method invokeMethod) {
        this.invokeMethod = invokeMethod;
    }

    public void setApiHelper(APIHelper apiHelper) {
        this.apiHelper = apiHelper;
    }

    public void setHeaders() {
        Map<String, Object> header = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        Iterator<String> headerNameIterator = headerNames.asIterator();
        while (headerNameIterator.hasNext()) {
            String headerName = headerNameIterator.next();
            header.put(headerName, request.getHeader(headerName));
        }

        // 获取响应方式
        switch (logOutputType) {
            case JSON:
                headers = JacksonUtils.toJSONString(header);
                break;
            case TO_STRING: header.toString();
                break;

        }

        this.headers = headers;
    }

    public String getHeaders() {
        return "headers";
    }

    public void setLogOutputType(LogOutputType logOutputType) {
        this.logOutputType = logOutputType;
    }

    public HttpServletRequest getRequest() {
        return request;
    }

    public HttpServletResponse getResponse() {
        return response;
    }

    public Object getResult() {
        return result;
    }

    public ProceedingJoinPoint getPoint() {
        return point;
    }

    public long getRequestTime() {
        return requestTime;
    }

    public ServletRequestAttributes getAttributes() {
        return attributes;
    }

    public Method getInvokeMethod() {
        return invokeMethod;
    }

    public APIHelper getApiHelper() {
        return apiHelper;
    }

    private void setHeaders(String headers) {
        this.headers = headers;
    }

    public LogOutputType getLogOutputType() {
        return logOutputType;
    }

    public String getId() {
        return id;
    }

    private void setId(String id) {
        this.id = id;
    }

    public CurrentPathVariable getPathVariable() {
        return pathVariable;
    }

    private void setPathVariable(CurrentPathVariable pathVariable) {
        this.pathVariable = pathVariable;
    }

    public CurrentQueryParam getQueryParam() {
        return queryParam;
    }

    private void setQueryParam(CurrentQueryParam queryParam) {
        this.queryParam = queryParam;
    }

    public CurrentBody getBody() {
        return body;
    }

    private void setBody(CurrentBody body) {
        this.body = body;
    }

    public Object[] getArgs() {
        return args;
    }
}

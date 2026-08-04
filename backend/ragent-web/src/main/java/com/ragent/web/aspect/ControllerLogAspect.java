package com.ragent.web.aspect;

import cn.dev33.satoken.stp.StpUtil;
import com.ragent.common.log.LogHelper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.UUID;

/**
 * Controller 切面：自动记录请求入参、耗时、异常，输出到 ELK 日志体系。
 * <p>
 * 覆盖 com.ragent.web.controller 下所有 public 方法，不侵入业务代码。
 * 自动向 MDC 注入 userId / module / action / traceId / ip，业务代码无需手动埋点。
 */
@Slf4j
@Aspect
@Component
public class ControllerLogAspect {

    private static final String[] MDC_KEYS = {"userId", "module", "action", "traceId", "ip"};

    /**
     * 环绕切面：记录 Controller 方法的调用日志。
     */
    @Around("execution(public * com.ragent.web.controller..*(..))")
    public Object logController(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        String className = signature.getDeclaringType().getSimpleName();
        String methodName = signature.getName();
        String module = leafPackage(signature);

        // 获取当前用户 ID（如果有登录态）
        String userId = "anon";
        try {
            if (StpUtil.isLogin()) {
                userId = StpUtil.getLoginIdAsString();
            }
        } catch (Exception ignored) {
            // Sa-Token 未初始化时不报错
        }

        RequestInfo req = requestInfo();
        String params = buildParams(signature, pjp.getArgs());

        // 注入 MDC 上下文：随日志写入 ES 结构字段，供日志查询按模块/用户/操作筛选
        LogHelper.setUserId(userId);
        LogHelper.setModule(module);
        LogHelper.setAction(methodName);
        LogHelper.setTraceId(UUID.randomUUID().toString());
        LogHelper.setIp(req.ip);
        try {
            log.info("[{}] {} {}.{} | userId={} | params={}",
                    req.method, req.url, className, methodName, userId, params);

            Object result = pjp.proceed();
            long elapsed = System.currentTimeMillis() - start;
            log.info("[{}] {}.{} | {}ms OK", req.method, className, methodName, elapsed);
            return result;
        } catch (Throwable e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[{}] {}.{} | {}ms ERROR | {}: {}",
                    req.method, className, methodName, elapsed,
                    e.getClass().getSimpleName(), e.getMessage());
            throw e;
        } finally {
            LogHelper.removeContext(MDC_KEYS);
        }
    }

    /** 取类所在包最后一段作为模块名（controller/service/...），与前端日志页的模块筛选对齐 */
    private String leafPackage(MethodSignature signature) {
        String pkg = signature.getDeclaringType().getPackageName();
        int idx = pkg.lastIndexOf('.');
        return idx >= 0 ? pkg.substring(idx + 1) : pkg;
    }

    private RequestInfo requestInfo() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest r = attrs.getRequest();
                String method = r.getMethod();
                return new RequestInfo(method, method + " " + r.getRequestURI(), r.getRemoteAddr());
            }
        } catch (Exception ignored) {
        }
        return new RequestInfo("-", "-", "-");
    }

    private record RequestInfo(String method, String url, String ip) {
    }

    /**
     * 把方法参数拼成 "a=1, b=..." 的摘要，跳过 HttpServletRequest / MultipartFile 等大对象。
     * 对 record 类 DTO 逐字段展开并对 password/secret/token 字段打码，避免密码明文进日志。
     */
    private String buildParams(MethodSignature signature, Object[] args) {
        if (args == null || args.length == 0) return "-";
        Parameter[] parameters = signature.getMethod().getParameters();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            String name = parameters[i].getName();
            Object val = args[i];
            if (val instanceof HttpServletRequest || val instanceof MultipartFile) {
                sb.append(name).append("=<omitted>");
            } else {
                sb.append(name).append("=").append(truncate(describe(val), 200));
            }
        }
        return sb.toString();
    }

    /** record 类逐字段展开并脱敏；基础类型直接 toString；其余对象走 toString */
    private String describe(Object val) {
        if (val == null) {
            return "null";
        }
        if (val instanceof CharSequence || val instanceof Number
                || val instanceof Boolean || val instanceof Character) {
            return val.toString();
        }
        if (val instanceof Record record) {
            RecordComponent[] components = record.getClass().getRecordComponents();
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < components.length; i++) {
                if (i > 0) sb.append(", ");
                RecordComponent comp = components[i];
                String compName = comp.getName();
                Object v;
                try {
                    v = comp.getAccessor().invoke(record);
                } catch (Exception e) {
                    v = "<err>";
                }
                String lower = compName.toLowerCase();
                if (lower.contains("password") || lower.contains("secret") || lower.contains("token")) {
                    sb.append(compName).append("=***");
                } else {
                    sb.append(compName).append("=").append(v == null ? "null" : truncate(v.toString(), 200));
                }
            }
            return sb.append("}").toString();
        }
        return val.toString();
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}

package com.errdeck.sdk.tracing;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * DataSource 를 감싸 SQL 실행을 스팬으로 남긴다(8.3). 라이브러리 없이 JDK 의 동적 프록시로 연결과 문장을 감싼다.
 * 스팬 설명은 값을 지운 SQL 이다. 같은 SQL 이 연속으로 실행되면 {@link ActiveTransaction} 이 하나로 접는다(8.5).
 */
public final class TracingDataSource {

    private static final Set<String> EXECUTE = Set.of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate",
            "executeBatch", "executeLargeBatch");
    private static final Pattern STRING = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern NUMBER = Pattern.compile("\\b\\d+(\\.\\d+)?\\b");
    private static final Pattern IN_LIST = Pattern.compile("\\(\\s*\\?(\\s*,\\s*\\?)+\\s*\\)");
    private static final int MAX_SQL = 1000;

    private TracingDataSource() {
    }

    public static DataSource wrap(DataSource target) {
        if (Proxy.isProxyClass(target.getClass()) && Proxy.getInvocationHandler(target) instanceof Handler) {
            return target;
        }
        return proxy(DataSource.class, target, (proxy, method, args) -> {
            Object result = invoke(target, method, args);
            return result instanceof Connection c ? wrapConnection(c) : result;
        });
    }

    private static Connection wrapConnection(Connection connection) {
        return proxy(Connection.class, connection, (proxy, method, args) -> {
            Object result = invoke(connection, method, args);
            String sql = args != null && args.length > 0 && args[0] instanceof String s ? s : null;
            if (result instanceof CallableStatement cs) {
                return wrapStatement(CallableStatement.class, cs, sql);
            }
            if (result instanceof PreparedStatement ps) {
                return wrapStatement(PreparedStatement.class, ps, sql);
            }
            if (result instanceof Statement st) {
                return wrapStatement(Statement.class, st, null);
            }
            return result;
        });
    }

    private static <T extends Statement> T wrapStatement(Class<T> type, T statement, String preparedSql) {
        return proxy(type, statement, (proxy, method, args) -> {
            ActiveTransaction tx = Tracing.current();
            if (tx == null || !EXECUTE.contains(method.getName())) {
                return invoke(statement, method, args);
            }
            String sql = preparedSql != null ? preparedSql : args != null && args.length > 0 && args[0] instanceof String s ? s : "(batch)";
            ActiveTransaction.OpenSpan span = tx.startSpan("db.sql", scrub(sql));
            try {
                return invoke(statement, method, args);
            } finally {
                tx.finishSpan(span);
            }
        });
    }

    /** 값(문자열, 숫자)을 ? 로 바꾸고 IN 목록을 하나로 줄인다. 같은 쿼리가 값만 달라도 같은 설명이 되어 접힌다. */
    static String scrub(String sql) {
        String s = STRING.matcher(sql).replaceAll("?");
        s = NUMBER.matcher(s).replaceAll("?");
        s = IN_LIST.matcher(s).replaceAll("(?)");
        s = s.replaceAll("\\s+", " ").strip();
        return s.length() <= MAX_SQL ? s : s.substring(0, MAX_SQL - 1) + "…";
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, T target, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, new Handler(handler));
    }

    /** 이미 감싼 것을 다시 감싸지 않으려고 표시한다. */
    private record Handler(InvocationHandler delegate) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return delegate.invoke(proxy, method, args);
        }
    }
}

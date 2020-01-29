package com.example.beanfactory.config;

import com.example.beanfactory.util.ApplicationX;
import com.example.beanfactory.util.ApplicationX.*;

import javax.sql.ConnectionEventListener;
import javax.sql.DataSource;
import javax.sql.PooledConnection;
import javax.sql.StatementEventListener;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * 模拟jdbc的配置
 * @author wangzihao
 */
@Configuration
public class JdbcConfig {

    @Lazy
    @Primary
    @Bean({"myPooledConnection","myPooledConnectionAlias1","myPooledConnectionAlias2"})
    public PooledConnection pooledConnection(ApplicationX app){
        Objects.requireNonNull(app);
        return new MyPooledConnection();
    }

//    @Primary
    @Lazy
    @Scope("prototype")
//    @Scope("singleton")
    @Bean
    public DataSource dataSource1(PooledConnection connection){
        Objects.requireNonNull(connection);
        return new MyDataSource("dataSource1");
    }

    @Primary
    @Lazy
//    @Scope("prototype")
    @Scope("singleton")
    @Bean
    public DataSource dataSource2(PooledConnection connection){
        Objects.requireNonNull(connection);
        return new MyDataSource("dataSource2");
    }

    public static class MyPooledConnection implements PooledConnection {
        @Autowired
        private ApplicationX app;
        @Override
        public Connection getConnection() throws SQLException {
            return null;
        }

        @Override
        public void close() throws SQLException {

        }

        @Override
        public void addConnectionEventListener(ConnectionEventListener listener) {

        }

        @Override
        public void removeConnectionEventListener(ConnectionEventListener listener) {

        }

        @Override
        public void addStatementEventListener(StatementEventListener listener) {

        }

        @Override
        public void removeStatementEventListener(StatementEventListener listener) {

        }
    }

    public static class MyDataSource implements DataSource {
        @Autowired
        private PooledConnection pooledConnection;
        @Autowired
        private ApplicationX app;
        private final String name;
        public MyDataSource(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return null;
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return null;
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return null;
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return false;
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {

        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {

        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return null;
        }
    }
}

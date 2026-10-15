package com.example.systemlog.config;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class DatabaseConfig {
    @Bean
    public DataSource dataSource(Environment environment) {
        String jdbc = environment.getProperty("spring.datasource.url");
        String username = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");
        if (jdbc == null) {
            String source = environment.getProperty("DATABASE_URL");
            if (source == null) throw new IllegalStateException("请在 .env 中配置 DATABASE_URL");
            URI uri = URI.create(source);
            if (!"postgresql".equals(uri.getScheme()) && !"postgres".equals(uri.getScheme()))
                throw new IllegalArgumentException("DATABASE_URL 必须使用 PostgreSQL");
            String[] credentials = uri.getRawUserInfo() == null ? new String[0] : uri.getRawUserInfo().split(":", 2);
            if (credentials.length != 2) throw new IllegalArgumentException("DATABASE_URL 需要用户名和密码");
            username = decode(credentials[0]);
            password = decode(credentials[1]);
            jdbc = "jdbc:postgresql://" + uri.getHost() + ":" + (uri.getPort() < 0 ? 5432 : uri.getPort())
                + uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        }
        HikariDataSource source = new HikariDataSource();
        source.setJdbcUrl(jdbc);
        source.setUsername(username);
        source.setPassword(password);
        source.setMaximumPoolSize(10);
        source.setConnectionTimeout(5000);
        source.setConnectionInitSql("SET TIME ZONE 'Asia/Shanghai'");
        return source;
    }
    private static String decode(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}

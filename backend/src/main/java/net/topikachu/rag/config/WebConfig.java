package net.topikachu.rag.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 关键：将所有非 API 的路径转发给 index.html 处理
        registry.addViewController("/{path:[^\\.]*}")
                .setViewName("forward:/index.html");
    }
}
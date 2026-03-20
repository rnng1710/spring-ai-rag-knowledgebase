package net.topikachu.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RequestPredicate;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration(proxyBeanMethods = false)
public class WebConfig {

    @Bean
    public RouterFunction<ServerResponse> spaRouter() {
        ClassPathResource indexHtml = new ClassPathResource("static/index.html");
        RequestPredicate spaPredicate = this::isSpaRequest;
        return RouterFunctions.route(spaPredicate,
                request -> ServerResponse.ok()
                        .contentType(MediaType.TEXT_HTML)
                        .bodyValue(indexHtml));
    }

    private boolean isSpaRequest(ServerRequest request) {
        String path = request.path();
        return request.method().name().equals("GET")
                && !path.startsWith("/api")
                && !path.startsWith("/actuator")
                && !path.contains(".");
    }
}

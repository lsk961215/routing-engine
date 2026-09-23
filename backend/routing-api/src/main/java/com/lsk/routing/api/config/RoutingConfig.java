package com.lsk.routing.api.config;

import com.lsk.routing.core.graph.RoutingGraph;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RoutingConfig {
    /** One immutable shared graph; invalid files fail application startup. */
    @Bean
    @ConditionalOnProperty(name = "routing.graph.path")
    public RoutingGraph routingGraph(@Value("${routing.graph.path}") String path) throws IOException {
        return RoutingGraph.read(Path.of(path));
    }
}

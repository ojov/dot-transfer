package com.ojo.dottransfer.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sends the bare root to the API documentation.
 *
 * <p>This service has no home page, so {@code GET /} was answering with a 404 for a missing static
 * resource - technically correct and completely unhelpful to the first person who opens the base
 * URL in a browser. Pointing it at the docs makes the obvious thing to try the useful one.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/docs");
    }
}

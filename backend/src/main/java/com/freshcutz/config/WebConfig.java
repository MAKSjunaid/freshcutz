package com.freshcutz.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/** Serves uploaded product photos back out at /uploads/products/&lt;file&gt;. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${freshcutz.uploads.dir}")
    private String uploadsDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String absolutePath = Paths.get(uploadsDir).toAbsolutePath().normalize().toString();
        registry.addResourceHandler("/uploads/products/**")
                .addResourceLocations("file:" + absolutePath + "/");
    }
}

package com.example.lgygateway.filters.init;

import com.example.lgygateway.filters.Filter;
import com.example.lgygateway.filters.impl.AuthFilter;
import com.example.lgygateway.filters.models.FilterChain;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;

@Component
@Data
public class FiltersInit {
    private FilterChain filterChain = new FilterChain();

    @PostConstruct
    public void init() {
        // 动态加载指定路径下的过滤器类
        loadFiltersFromPath();
    }

    private void loadFiltersFromPath() {
        try {
            File filtersDir = new File("src/main/java/com/example/lgygateway/filters");
            if (!filtersDir.exists() || !filtersDir.isDirectory()) {
                throw new IllegalArgumentException("Invalid filters directory path: " + "target/classes/com/example/lgygateway/filters");
            }

            File[] files = filtersDir.listFiles((dir, name) -> name.endsWith(".class"));
            if (files == null) {
                return;
            }
            URL[] urls = {filtersDir.toURI().toURL()};
            URLClassLoader classLoader = new URLClassLoader(urls);

            for (File file : files) {
                String className = file.getName().replace(".class", "");
                Class clazz = classLoader.loadClass(className);
                if (Filter.class.isAssignableFrom(clazz)) {
                    Filter filter = (Filter) clazz.getDeclaredConstructor().newInstance();
                    filterChain.addFilter(filter);
                }
            }
        } catch (Exception e) {
            e.fillInStackTrace();
        }
    }
}

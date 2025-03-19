package com.example.lgygateway.filters.init;

import com.example.lgygateway.filters.Filter;
import com.example.lgygateway.filters.models.FilterChain;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ServiceLoader;

@Component
@Data
public class FiltersInit {
    private FilterChain filterChain = new FilterChain();
    @PostConstruct
    public void init() {
        // 使用 ServiceLoader 加载 SPI 实现类
        loadFiltersBySPI();
    }

    private void loadFiltersBySPI() {
        ServiceLoader<Filter> loader = ServiceLoader.load(Filter.class);
        for (Filter filter : loader) {
            filterChain.addFilter(filter);
        }
    }
}

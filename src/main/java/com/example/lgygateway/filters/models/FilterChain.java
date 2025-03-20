package com.example.lgygateway.filters.models;

import com.example.lgygateway.filters.Filter;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@Getter
public class FilterChain {
    private final List<Filter> filters = new ArrayList<>();
    int index = 0;
    public void addFilter(Filter filter) {
        filters.add(filter);
    }
    public void doFilter(FullContext context) {
        try {
            if (index < filters.size()) {
                Filter filter = filters.get(index++);
                filter.filter(context, this);
            }
        }finally {
            index = 0;
        }
    }

}

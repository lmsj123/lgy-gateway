package com.example.lgygateway.filters.models;

import com.example.lgygateway.filters.Filter;

import java.util.ArrayList;
import java.util.List;


public class FilterChain {
    private final List<Filter> filters = new ArrayList<>();
    int index = 0;
    public void addFilter(Filter filter) {
        filters.add(filter);
    }
    public void doFilter(FullContext context) {
        if (index < filters.size()) {
            Filter filter = filters.get(index++);
            filter.filter(context,this);
        }
    }

}

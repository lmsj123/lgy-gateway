package com.example.lgygateway;


import com.alibaba.nacos.api.naming.pojo.Instance;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class LgyGatewayApplicationTests {

    public static void main(String[] args) {
        LgyGatewayApplicationTests test = new LgyGatewayApplicationTests();
        String matchedPattern = "/api/*/detail/**";
        String oldUrl = "/api/x/detail/y?count=1";
        test.buildTargetUrl(oldUrl,matchedPattern,new Instance());
    }
    private String convertToRegex(String pattern) {
        // /python/.*
        // 步骤1: 将 ** 替换为临时标记
        String tempMarker = UUID.randomUUID().toString();
        String temp = pattern.replace("**", tempMarker);
        // 步骤2: 处理其他转义和替换
        String regex = temp
                .replace(".", "\\.")
                .replace("*", "[^/]*");
        // 步骤3: 将临时标记替换为 .*
        regex = regex.replace(tempMarker, ".*");
        return "^" + regex + "(\\?.*)?$";
    }
    private String buildTargetUrl(String originalUrl, String matchedPattern, Instance instance) {
        // 分离路径和查询参数
        String path = originalUrl.split("\\?")[0];
        String query = originalUrl.contains("?") ? originalUrl.split("\\?")[1] : "";

        // 生成正则表达式并验证完整匹配
        String regexPattern = convertToRegex(matchedPattern);
        Pattern pattern = Pattern.compile(regexPattern);
        Matcher matcher = pattern.matcher(path);

        if (matcher.matches()) {  // 使用 matches() 确保完整匹配
            // 提取动态路径部分（如 /python/cr_data_backflow/... → cr_data_backflow/...）
            String prefixRegex = convertToRegex(matchedPattern.replace("**", ""));
            String backendPath = path.replaceFirst(prefixRegex, "");
            // 确保路径以斜杠开头
            if (!backendPath.startsWith("/")) {
                backendPath = "/" + backendPath;
            }
            return "http://" + instance.getIp() + ":" + instance.getPort()
                    + backendPath + (query.isEmpty() ? "" : "?" + query);
        }
        throw new IllegalArgumentException("URL does not match the pattern: " + matchedPattern);
    }
    /*
       /python/123/*  /python/**   /xxxx/** /yyyy/**
      python -> 123,** -> **


    /python/123/456

     */
}

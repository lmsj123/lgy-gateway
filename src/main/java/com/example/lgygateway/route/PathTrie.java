package com.example.lgygateway.route;

import com.alibaba.nacos.api.naming.pojo.Instance;
import lombok.Data;

import java.util.*;

@Data
public class PathTrie {
    public static void main(String[] args) {
        PathTrie trie = new PathTrie();

        // 插入通配规则
        ArrayList<Instance> arrayList1 = new ArrayList<>();
        Instance instance1 = new Instance();
        instance1.setIp("192.168.1.1");
        instance1.setPort(8080);
        arrayList1.add(instance1);

        ArrayList<Instance> arrayList2 = new ArrayList<>();
        Instance instance2 = new Instance();
        instance2.setIp("192.168.1.2");
        instance2.setPort(8080);
        arrayList2.add(instance2);

        ArrayList<Instance> arrayList3 = new ArrayList<>();
        Instance instance3 = new Instance();
        instance3.setIp("192.168.1.3");
        instance3.setPort(8080);
        arrayList3.add(instance3);

        ArrayList<Instance> arrayList4 = new ArrayList<>();
        Instance instance4 = new Instance();
        instance4.setIp("192.168.1.4");
        instance4.setPort(8080);
        arrayList4.add(instance4);

        ArrayList<Instance> arrayList5 = new ArrayList<>();
        Instance instance5 = new Instance();
        instance5.setIp("192.168.1.5");
        instance5.setPort(8080);
        arrayList5.add(instance5);

        ArrayList<Instance> arrayList6 = new ArrayList<>();
        Instance instance6 = new Instance();
        instance6.setIp("192.168.1.6");
        instance6.setPort(8080);
        arrayList6.add(instance6);

        trie.insert("/python/*", arrayList1);      // 单层匹配
        trie.insert("/python/**", arrayList2);     // 多层匹配
        trie.insert("/python/apis/v1", arrayList3);// 精确匹配
        trie.insert("/python/apis/*", arrayList4); // 新增单层规则
        trie.insert("/python/apis/**", arrayList5); // 新增多层规则
        trie.insert("/", arrayList6);// 插入根路径规则
//        List<Instance> search = trie.searchPathTrie("/python/apis/v1").instances;
//        List<Instance> search = trie.searchPathTrie("/python/apis").instances;
//        List<Instance> search = trie.searchPathTrie("/python/apis/v1/data").instances;
//        List<Instance> search = trie.searchPathTrie("/python/apis/new").instances;
//        List<Instance> search = trie.searchPathTrie("/").instances;
//        List<Instance> search = trie.searchPathTrie("/python/a/b/c/d/e/f").instances;
//        List<Instance> search = trie.searchPathTrie("").instances;
        List<Instance> search = trie.searchPathTrie("/unknown").instances;
        System.out.println(Arrays.toString(search.toArray()));
        // 验证匹配
    }

    private Map<String, PathTrie> children = new HashMap<>();
    private ArrayList<Instance> instances = new ArrayList<>();
    private String finalPath;
    // 新增通配符标识

    public void insert(String url, List<Instance> instances) {
        String[] parts = url.split("/");
        PathTrie pathTrie = this;
        for (int i = 1; i < parts.length; i++) {
            if (!pathTrie.children.containsKey(parts[i])) {
                PathTrie trie = new PathTrie();
                pathTrie.children.put(parts[i], trie);
            }
            pathTrie = pathTrie.children.get(parts[i]);
        }
        pathTrie.instances = new ArrayList<>(instances);
        pathTrie.finalPath = url;
    }
    public List<Instance> search(String url) {
        String[] parts = url.split("/");
        PathTrie pathTrie = this;
        List<Instance> list = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            // 得到与当前路径最近的 ** 对应的服务实例
            if (pathTrie.children.containsKey("**")) {
                list = pathTrie.children.get("**").instances;
            }
            if (pathTrie.children.containsKey(parts[i])) {
                // 得到平级 * 对应的实例
                List<Instance> instanceList = new ArrayList<>();
                if (pathTrie.children.containsKey("*")) {
                    instanceList = pathTrie.children.get("*").instances;
                }
                pathTrie = pathTrie.children.get(parts[i]);
                // 如果出现了路由规则存在 /xxxx/y/z 而 实际为/xxxx/y
                if (i == parts.length - 1 && pathTrie.instances.isEmpty()) {
                    // 优先级为 /xxxx/* -> /xxxx/y/** -> /xxxx/**
                    if (!instanceList.isEmpty()) {
                        return instanceList;
                    } else if (pathTrie.children.containsKey("**")) {
                        return pathTrie.children.get("**").instances;
                    } else {
                        return list;
                    }
                }
            } else if (pathTrie.children.containsKey("*")) {
                if (i == parts.length - 1) {
                    return pathTrie.children.get("*").instances;
                }
            } else if (pathTrie.children.containsKey("**")) {
                return pathTrie.children.get("**").instances;
            } else {
                return list;
            }
        }
        return pathTrie.instances;
    }
    public PathTrie searchPathTrie(String url) {
        if (url.isEmpty()) {
            return new PathTrie();
        }
        String[] parts = url.split("/");
        PathTrie pathTrie = this;
        PathTrie trie = new PathTrie();
        for (int i = 1; i < parts.length; i++) {
            // 得到与当前路径最近的 ** 对应的服务实例
            if (pathTrie.children.containsKey("**")) {
                trie = pathTrie.children.get("**");
            }
            if (pathTrie.children.containsKey(parts[i])) {
                // 得到平级 * 对应的实例
                PathTrie pre = new PathTrie();
                if (pathTrie.children.containsKey("*")) {
                    pre = pathTrie.children.get("*");
                }
                pathTrie = pathTrie.children.get(parts[i]);
                // 如果出现了路由规则存在 /xxxx/y/z 而 实际为/xxxx/y
                if (i == parts.length - 1 && pathTrie.instances.isEmpty()) {
                    // 优先级为 /xxxx/* -> /xxxx/y/** -> /xxxx/**
                    if (!pre.instances.isEmpty()) {
                        return pre;
                    } else if (pathTrie.children.containsKey("**")) {
                        return pathTrie.children.get("**");
                    } else {
                        return trie;
                    }
                }
            } else if (pathTrie.children.containsKey("*")) {
                if (i == parts.length - 1) {
                    return pathTrie.children.get("*");
                }
                return trie;
            } else if (pathTrie.children.containsKey("**")) {
                return pathTrie.children.get("**");
            } else {
                return trie;
            }
        }
        return pathTrie;
    }
}
package com.example.lgygateway.route;

import com.alibaba.nacos.api.naming.pojo.Instance;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
// 能识别更复杂的路由规则
@Data
public class PathTrieBetter {
    private Map<String, PathTrieBetter> children = new HashMap<>();
    private ArrayList<Instance> instances = new ArrayList<>();
    private String finalPath;
    private boolean isWildcard; // 新增标识：是否是通配节点

    public void insert(String url, List<Instance> instances) {
        String[] parts = url.split("/");
        PathTrieBetter current = this;
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            boolean isWildcard = part.equals("*") || part.equals("**");

            // 处理通配符标识
            if (!current.children.containsKey(part)) {
                PathTrieBetter newNode = new PathTrieBetter();
                newNode.setWildcard(isWildcard);
                current.children.put(part, newNode);
            }
            current = current.children.get(part);
        }
        current.instances = new ArrayList<>(instances);
        current.finalPath = url;
    }

    public PathTrieBetter searchPathTrie(String url) {
        String[] searchParts = url.split("/");
        return searchRecursive(this, searchParts, 1, null);
    }

    private PathTrieBetter searchRecursive(PathTrieBetter node, String[] parts, int index, PathTrieBetter bestMatch) {
        // 终止条件：遍历完所有路径段
        if (index >= parts.length) {
            return node.instances.isEmpty() ? bestMatch : node;
        }

        String currentPart = parts[index];

        // 精确匹配优先
        if (node.children.containsKey(currentPart)) {
            PathTrieBetter exactMatch = searchRecursive(
                    node.children.get(currentPart), parts, index + 1, bestMatch
            );
            if (exactMatch != null && !exactMatch.instances.isEmpty()) {
                bestMatch = exactMatch;
            }
        }

        // 通配符匹配（* 和 **）
        for (Map.Entry<String, PathTrieBetter> entry : node.children.entrySet()) {
            String key = entry.getKey();
            PathTrieBetter child = entry.getValue();

            if (child.isWildcard) {
                // 单层通配符 *
                if (key.equals("*")) {
                    PathTrieBetter starMatch = searchRecursive(child, parts, index + 1, bestMatch);
                    if (starMatch != null && (bestMatch == null ||
                            starMatch.finalPath.length() > bestMatch.finalPath.length())) {
                        bestMatch = starMatch;
                    }
                }
                // 多层通配符 **
                else if (key.equals("**")) {
                    // ** 匹配剩余所有路径
                    PathTrieBetter doubleStarMatch = child;
                    if (child.instances.isEmpty()) {
                        // 继续向后查找更精确的匹配
                        doubleStarMatch = searchRecursive(child, parts, parts.length, bestMatch);
                    }
                    if (doubleStarMatch != null && (bestMatch == null ||
                            child.finalPath.length() > bestMatch.finalPath.length())) {
                        bestMatch = doubleStarMatch;
                    }
                }
            }
        }

        return bestMatch;
    }
}

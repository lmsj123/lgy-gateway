package com.example.lgygateway.registryStrategy.impl.nacos;

import com.example.lgygateway.registryStrategy.impl.nacos.NacosRegistry.RouteData;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
@SuppressWarnings("all")
public class VersionLRUList {
    public static void main(String[] args) {
        VersionLRUList cache = new VersionLRUList(2);
        cache.add(1.0, new RouteData(new ConcurrentHashMap<>(), new ConcurrentHashMap<>()));
        cache.add(2.0, new RouteData(new ConcurrentHashMap<>(), new ConcurrentHashMap<>()));
        cache.get(1.0);
        cache.add(3.0, new RouteData(new ConcurrentHashMap<>(), new ConcurrentHashMap<>()));  // 容量超限，应删除1.0
        System.out.println(cache.get(2.0));
        System.out.println(cache.get(1.0));
    }
    private class Node{
        double version;
        RouteData routeData;
        Node next;
        Node prev;
        public Node(){}
        public Node(double version,RouteData routeData){
            this.routeData = routeData;
            this.version = version;
        }
    }
    private class LRUList{
        Node head;
        Node tail;
        public LRUList(){
            head = new Node();  // 头哨兵
            tail = new Node();  // 尾哨兵
            head.next = tail;
            tail.prev = head;
        }
        public void addFirst(Node node){
            Node next = head.next;
            node.next = next;
            next.prev = node;
            head.next = node;
            node.prev = head;
        }
        public Node removeLast(){
            Node last = tail.prev;
            remove(last);
            return last;
        }
        public void remove(Node node){
            Node next = node.next;
            Node prev = node.prev;
            prev.next = next;
            next.prev = prev;
        }
    }
    private int size;
    public VersionLRUList(int size){
        if (size <= 0){
            throw new IllegalArgumentException("Size must be positive");
        }
        this.size = size;
    }
    private final ConcurrentHashMap<Double,Node> map = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final LRUList lruList = new LRUList();
    public void add(double version, RouteData routeData){
        try {
            lock.lock();
            if (map.containsKey(version)){
                Node cur = map.get(version);
                cur.routeData = routeData;
                lruList.remove(cur);
                lruList.addFirst(cur);
            }else {
                Node node = new Node(version, routeData);
                map.put(version, node);
                lruList.addFirst(node);
                if (map.size() > size){
                    Node deleted = lruList.removeLast();
                    map.remove(deleted.version);
                }
            }
        }finally {
            lock.unlock();
        }

    }
    public RouteData get(double version){
        try {
            lock.lock();
            if (map.containsKey(version)){
                Node node = map.get(version);
                lruList.remove(node);
                lruList.addFirst(node);
                return node.routeData;
            }else {
                return null;
            }
        }finally {
            lock.unlock();
        }
    }
}

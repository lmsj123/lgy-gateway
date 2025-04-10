package com.example.lgygateway.registryStrategy.impl.nacos;


import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
@SuppressWarnings("all")
public class VersionLRUList {
    private class Node{
        double version;
        String content;
        Node next;
        Node prev;
        public Node(){}
        public Node(double version,String content){
            this.content = content;
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
    public void add(double version, String content){
        try {
            lock.lock();
            if (map.containsKey(version)){
                Node cur = map.get(version);
                cur.content = content;
                lruList.remove(cur);
                lruList.addFirst(cur);
            }else {
                Node node = new Node(version, content);
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
    public String get(double version){
        try {
            lock.lock();
            if (map.containsKey(version)){
                Node node = map.get(version);
                lruList.remove(node);
                lruList.addFirst(node);
                return node.content;
            }else {
                return null;
            }
        }finally {
            lock.unlock();
        }
    }
}

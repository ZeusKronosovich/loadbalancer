package my.workers.loadbalancer.strategy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

import my.workers.loadbalancer.model.BackendServer;

public class ConsistentHashingStrategy implements LoadBalancingStrategy {
    private final int virtualNodes;

    public ConsistentHashingStrategy() {
        this(150);
    }

    public ConsistentHashingStrategy(int virtualNodes) {
        this.virtualNodes = virtualNodes;
    }

    @Override
    @SuppressWarnings("null")
    public BackendServer selectBackend(List<BackendServer> backends, Object key) {
        List<BackendServer> alive = backends.stream()
                .filter(BackendServer::isAlive)
                .toList();
        if (alive.isEmpty()) {
            return null;
        }

        TreeMap<Long, BackendServer> ring = new TreeMap<>();
        for (BackendServer server : alive) {
            String base = server.getHost() + ":" + server.getPort();
            for (int i = 0; i < virtualNodes; i++) {
                String virtualKey = base + "#" + i;
                long hash = hash(virtualKey);
                ring.put(hash, server);
            }
        }

        long hashKey = hash(key.toString());
        SortedMap<Long, BackendServer> tail = ring.tailMap(hashKey);
        Long nodeHash = tail.isEmpty() ? ring.firstKey() : tail.firstKey();
        return ring.get(nodeHash);
    }

    private long hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            long hash = 0;
            for (int i = 0; i < 8; i++) {
                hash = (hash << 8) | (digest[i] & 0xFF);
            }
            return hash;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }
}
package my.workers.loadbalancer.strategy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import my.workers.loadbalancer.model.BackendServer;

public class WeightedRoundRobinStrategy implements LoadBalancingStrategy {
    private final Map<String, Integer> weights = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(0);

    public WeightedRoundRobinStrategy() {
    }

    public void setWeight(String host, int port, int weight) {
        String key = host + ":" + port;
        weights.put(key, weight);
    }

    public int getWeight(String host, int port) {
        String key = host + ":" + port;
        return weights.getOrDefault(key, 1);
    }
    
    public Map<String, Integer> getAllWeights() {
        return new ConcurrentHashMap<>(weights);
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

        int totalWeight = alive.stream()
                .mapToInt(b -> getWeight(b.getHost(), b.getPort()))
                .sum();

        if (totalWeight <= 0) {
            int index = counter.getAndIncrement() % alive.size();
            return alive.get(index);
        }

        int current = counter.getAndIncrement() % totalWeight;
        int cumulative = 0;
        for (BackendServer backend : alive) {
            int weight = getWeight(backend.getHost(), backend.getPort());
            cumulative += weight;
            if (current < cumulative) {
                return backend;
            }
        }
        return alive.get(0);
    }

    @Override
    public String toString() {
        return "WeightedRoundRobinStrategy{weights=" + weights + "}";
    }
}
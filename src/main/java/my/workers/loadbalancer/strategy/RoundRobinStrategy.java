package my.workers.loadbalancer.strategy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import my.workers.loadbalancer.model.BackendServer;

public class RoundRobinStrategy implements LoadBalancingStrategy {
    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    @SuppressWarnings("null")
    public BackendServer selectBackend(List<BackendServer> backends, Object key) {
        List<BackendServer> alive = backends.stream()
                .filter(BackendServer::isAlive)
                .toList();
        if (alive.isEmpty()) {
            return null;
        }
        int index = counter.getAndIncrement() % alive.size();
        return alive.get(index);
    }
}
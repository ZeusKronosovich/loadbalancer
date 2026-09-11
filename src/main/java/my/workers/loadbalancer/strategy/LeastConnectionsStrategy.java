package my.workers.loadbalancer.strategy;

import java.util.Comparator;
import java.util.List;

import my.workers.loadbalancer.model.BackendServer;

public class LeastConnectionsStrategy implements LoadBalancingStrategy {
    @Override
    @SuppressWarnings("null")
    public BackendServer selectBackend(List<BackendServer> backends, Object key) {
        return backends.stream()
                .filter(BackendServer::isAlive)
                .min(Comparator.comparingInt(BackendServer::getActiveConnections))
                .orElse(null);
    }
}
package my.workers.loadbalancer.strategy;

import java.util.List;

import my.workers.loadbalancer.model.BackendServer;

public interface LoadBalancingStrategy {
    BackendServer selectBackend(List<BackendServer> backends, Object key);
}
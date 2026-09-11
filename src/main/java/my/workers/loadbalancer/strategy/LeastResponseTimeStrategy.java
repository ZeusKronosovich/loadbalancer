package my.workers.loadbalancer.strategy;

import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import my.workers.loadbalancer.model.BackendServer;

public class LeastResponseTimeStrategy implements LoadBalancingStrategy {
    private static final Logger log = LoggerFactory.getLogger(LeastResponseTimeStrategy.class);
    
    private final ConcurrentHashMap<String, AtomicLong> requestStartTimes = new ConcurrentHashMap<>();
    private final Random random = new Random();
    
    private static final int MIN_REQUESTS_FOR_STATISTICS = 10;
    private static final double EWMA_ALPHA = 0.3;
    private static final long MAX_RESPONSE_TIME_MS = 60000;
    
    private static final double EXPLORATION_RATE = 0.15;
    private static final int MIN_REQUESTS_FOR_EXPLORATION = 5;
    
    private static final double RPS_PENALTY_WEIGHT = 0.5;
    private static final long RPS_WINDOW_MS = 10000;
    
    private final ConcurrentHashMap<String, AtomicLong> requestCountWindow = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> windowStartTime = new ConcurrentHashMap<>();
    
    @Override
    @SuppressWarnings("null")
    public BackendServer selectBackend(List<BackendServer> backends, Object key) {
        List<BackendServer> alive = backends.stream()
                .filter(BackendServer::isAlive)
                .toList();
        
        if (alive.isEmpty()) {
            return null;
        }
        
        if (alive.size() == 1) {
            return alive.get(0);
        }
        
        if (shouldExplore(alive)) {
            BackendServer explored = selectExplorationTarget(alive);
            if (explored != null) {
                log.debug("Exploration: selected backend {}:{} for random probing", 
                    explored.getHost(), explored.getPort());
                return explored;
            }
        }
        
        return selectWithRpsPenalty(alive);
    }
    
    private boolean shouldExplore(List<BackendServer> alive) {
        if (random.nextDouble() < EXPLORATION_RATE) {
            boolean hasColdBackend = alive.stream()
                    .anyMatch(b -> b.getTotalRequests() < MIN_REQUESTS_FOR_EXPLORATION);
            
            if (hasColdBackend) {
                return true;
            }
            
            return true;
        }
        
        return false;
    }
    
    @SuppressWarnings("null")
    private BackendServer selectExplorationTarget(List<BackendServer> alive) {
        if (random.nextDouble() < 0.7) {
            return alive.stream()
                    .min(Comparator.comparingLong(BackendServer::getTotalRequests))
                    .orElse(null);
        } else {
            return alive.get(random.nextInt(alive.size()));
        }
    }
    
    @SuppressWarnings("null")
    private BackendServer selectWithRpsPenalty(List<BackendServer> alive) {
        updateRpsWindows(alive);
        
        boolean hasColdStart = alive.stream()
                .anyMatch(b -> b.getTotalRequests() < MIN_REQUESTS_FOR_STATISTICS);
        
        if (hasColdStart) {
            log.debug("Cold start detected (some backends have < {} requests), using exploration mode", 
                    MIN_REQUESTS_FOR_STATISTICS);
            return selectExplorationTarget(alive);
        }
        
        BackendServer selected = alive.stream()
                .min(Comparator.comparingDouble(this::calculateScore))
                .orElse(null);
        
        if (selected != null && log.isDebugEnabled()) {
            double rps = getRps(selected);
            double score = calculateScore(selected);
            log.debug("Selected backend {}:{} with avgRT={:.2f}ms, rps={:.2f}, connections={}, score={:.2f}", 
                    selected.getHost(), selected.getPort(),
                    selected.getAverageResponseTime(),
                    rps,
                    selected.getActiveConnections(),
                    score);
        }
        
        return selected;
    }
    
    private double calculateScore(BackendServer backend) {
        double avgResponseTime = backend.getAverageResponseTime();
        
        double rps = getRps(backend);
        double rpsPenalty = rps * RPS_PENALTY_WEIGHT;
        
        double connectionPenalty = backend.getActiveConnections() * 5.0;
        
        double requestCountPenalty = Math.log(backend.getTotalRequests() + 1) * 2.0;
        
        return avgResponseTime + rpsPenalty + connectionPenalty + requestCountPenalty;
    }
    
    private double getRps(BackendServer backend) {
        String key = backend.getHost() + ":" + backend.getPort();
        AtomicLong count = requestCountWindow.get(key);
        Long startTime = windowStartTime.get(key);
        
        if (count == null || startTime == null) {
            return 0.0;
        }
        
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed <= 0) {
            return 0.0;
        }
        
        return (double) count.get() / (elapsed / 1000.0);
    }
    
    private void updateRpsWindows(List<BackendServer> backends) {
        long now = System.currentTimeMillis();
        
        for (BackendServer backend : backends) {
            String key = backend.getHost() + ":" + backend.getPort();
            
            Long startTime = windowStartTime.get(key);
            if (startTime == null) {
                windowStartTime.put(key, now);
                requestCountWindow.computeIfAbsent(key, k -> new AtomicLong());
            } else if (now - startTime > RPS_WINDOW_MS) {
                windowStartTime.put(key, now);
                requestCountWindow.get(key).set(0);
            }
        }
    }
    
    public void recordRequestStart(BackendServer backend) {
        if (backend == null) return;
        String key = backend.getHost() + ":" + backend.getPort();
        requestStartTimes.computeIfAbsent(key, k -> new AtomicLong())
                .set(System.currentTimeMillis());
        
        requestCountWindow.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }
    
    public void recordRequestEnd(BackendServer backend, boolean success) {
        if (backend == null) return;
        
        String key = backend.getHost() + ":" + backend.getPort();
        AtomicLong startTime = requestStartTimes.get(key);
        
        if (startTime == null || startTime.get() <= 0) {
            return;
        }
        
        long duration = System.currentTimeMillis() - startTime.get();
        startTime.set(0);
        
        if (!success) {
            log.trace("Request to {}:{} failed, not recording response time", 
                    backend.getHost(), backend.getPort());
            return;
        }
        
        if (duration <= 0 || duration > MAX_RESPONSE_TIME_MS) {
            log.trace("Ignoring abnormal response time {}ms for {}:{}", 
                    duration, backend.getHost(), backend.getPort());
            return;
        }
        
        backend.recordResponseTime(duration);
        
        if (log.isTraceEnabled()) {
            log.trace("Recorded response time {}ms for {}:{}, new avg={:.2f}ms", 
                    duration, backend.getHost(), backend.getPort(), 
                    backend.getAverageResponseTime());
        }
    }
    
    public void cleanupStartTimes(String backendKey) {
        requestStartTimes.remove(backendKey);
        requestCountWindow.remove(backendKey);
        windowStartTime.remove(backendKey);
    }
    
    @Override
    public String toString() {
        return "LeastResponseTimeStrategy{" +
                "explorationRate=" + EXPLORATION_RATE +
                ", rpsPenaltyWeight=" + RPS_PENALTY_WEIGHT +
                ", rpsWindow=" + RPS_WINDOW_MS + "ms" +
                ", minRequests=" + MIN_REQUESTS_FOR_STATISTICS +
                ", alpha=" + EWMA_ALPHA +
                "}";
    }
}
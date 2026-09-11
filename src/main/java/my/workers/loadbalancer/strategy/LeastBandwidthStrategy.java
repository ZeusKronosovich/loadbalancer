package my.workers.loadbalancer.strategy;

import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import my.workers.loadbalancer.model.BackendServer;

public class LeastBandwidthStrategy implements LoadBalancingStrategy {
    private static final Logger log = LoggerFactory.getLogger(LeastBandwidthStrategy.class);
    
    private final Random random = new Random();
    
    private static final int MIN_BYTES_FOR_STATISTICS = 1024 * 5;
    private static final double CONNECTION_PENALTY_WEIGHT = 0.3;
    
    private static final double EXPLORATION_RATE = 0.10;
    
    private static final double REQUESTS_PENALTY_WEIGHT = 0.2;
    
    private static final double SMOOTHING_ALPHA = 0.3;
    private final ConcurrentHashMap<String, Double> smoothedBandwidth = new ConcurrentHashMap<>();
    
    private final ConcurrentHashMap<String, AtomicLong> requestCountRecent = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> requestCountResetTime = new ConcurrentHashMap<>();
    private static final long REQUEST_COUNT_WINDOW_MS = 5000;
    
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
                log.debug("Exploration: selected backend {}:{} for bandwidth probing", 
                    explored.getHost(), explored.getPort());
                return explored;
            }
        }
        
        return selectWithPenalties(alive);
    }
    
    private boolean shouldExplore(List<BackendServer> alive) {
        if (random.nextDouble() < EXPLORATION_RATE) {
            return true;
        }
        
        boolean hasColdBackend = alive.stream()
                .anyMatch(b -> b.getTotalBytesTransferred() < MIN_BYTES_FOR_STATISTICS);
        
        if (hasColdBackend) {
            return true;
        }
        
        boolean hasIdleBackend = alive.stream()
                .anyMatch(b -> b.getCurrentBandwidth() < 100);
        
        return hasIdleBackend;
    }
    
    @SuppressWarnings("null")
    private BackendServer selectExplorationTarget(List<BackendServer> alive) {
        double roll = random.nextDouble();
        
        if (roll < 0.6) {
            return alive.stream()
                    .min(Comparator.comparingLong(BackendServer::getTotalBytesTransferred))
                    .orElse(null);
        } else if (roll < 0.8) {
            return alive.stream()
                    .min(Comparator.comparingDouble(b -> {
                        double bw = b.getCurrentBandwidth();
                        String backendKey = b.getHost() + ":" + b.getPort();
                        Double smoothed = smoothedBandwidth.get(backendKey);
                        if (smoothed != null) {
                            return smoothed;
                        }
                        return bw;
                    }))
                    .orElse(null);
        } else {
            return alive.get(random.nextInt(alive.size()));
        }
    }
    
    @SuppressWarnings("null")
    private BackendServer selectWithPenalties(List<BackendServer> alive) {
        updateRequestCounters(alive);
        
        BackendServer selected = alive.stream()
                .min(Comparator.comparingDouble(this::calculateBandwidthScore))
                .orElse(null);
        
        if (selected != null && log.isDebugEnabled()) {
            double score = calculateBandwidthScore(selected);
            double smoothed = getSmoothedBandwidth(selected);
            long recentRequests = getRecentRequests(selected);
            log.debug("Selected backend {}:{} with bandwidth={:.2f} B/s, smoothed={:.2f}, " +
                     "recentRequests={}, connections={}, score={:.2f}", 
                    selected.getHost(), selected.getPort(),
                    selected.getCurrentBandwidth(),
                    smoothed,
                    recentRequests,
                    selected.getActiveConnections(),
                    score);
        }
        
        return selected;
    }
    
    private double calculateBandwidthScore(BackendServer backend) {
        double smoothed = getSmoothedBandwidth(backend);
        
        long recentRequests = getRecentRequests(backend);
        double requestsPenalty = recentRequests * REQUESTS_PENALTY_WEIGHT;
        
        double connectionPenalty = backend.getActiveConnections() * CONNECTION_PENALTY_WEIGHT * 1024;
        
        double bytesPenalty = Math.log(backend.getTotalBytesTransferred() + 1) * 100;
        
        return smoothed + requestsPenalty + connectionPenalty + bytesPenalty;
    }
    
    private double getSmoothedBandwidth(BackendServer backend) {
        String key = backend.getHost() + ":" + backend.getPort();
        double current = backend.getCurrentBandwidth();
        Double existing = smoothedBandwidth.get(key);
        
        if (existing == null) {
            smoothedBandwidth.put(key, current);
            return current;
        }
        
        double smoothed = SMOOTHING_ALPHA * current + (1 - SMOOTHING_ALPHA) * existing;
        smoothedBandwidth.put(key, smoothed);
        return smoothed;
    }
    
    private long getRecentRequests(BackendServer backend) {
        String key = backend.getHost() + ":" + backend.getPort();
        AtomicLong count = requestCountRecent.get(key);
        return count != null ? count.get() : 0;
    }
    
    private void updateRequestCounters(List<BackendServer> backends) {
        long now = System.currentTimeMillis();
        
        for (BackendServer backend : backends) {
            String key = backend.getHost() + ":" + backend.getPort();
            
            Long resetTime = requestCountResetTime.get(key);
            if (resetTime == null || now - resetTime > REQUEST_COUNT_WINDOW_MS) {
                requestCountRecent.computeIfAbsent(key, k -> new AtomicLong()).set(0);
                requestCountResetTime.put(key, now);
            }
        }
    }
    
    public void recordBytesTransferred(BackendServer backend, long bytes) {
        if (backend != null && bytes > 0) {
            backend.recordBytesTransferred(bytes);
            
            String key = backend.getHost() + ":" + backend.getPort();
            requestCountRecent.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
            
            getSmoothedBandwidth(backend);
            
            if (log.isTraceEnabled()) {
                log.trace("Recorded {} bytes for {}:{}, smoothed bandwidth: {:.2f} B/s", 
                        bytes, backend.getHost(), backend.getPort(),
                        getSmoothedBandwidth(backend));
            }
        }
    }
    
    public void resetMetrics(List<BackendServer> backends) {
        for (BackendServer backend : backends) {
            backend.resetBandwidthMetrics();
            String key = backend.getHost() + ":" + backend.getPort();
            smoothedBandwidth.remove(key);
            requestCountRecent.remove(key);
            requestCountResetTime.remove(key);
        }
        log.info("Bandwidth metrics reset");
    }
    
    @Override
    public String toString() {
        return "LeastBandwidthStrategy{" +
                "explorationRate=" + EXPLORATION_RATE +
                ", requestsPenaltyWeight=" + REQUESTS_PENALTY_WEIGHT +
                ", smoothingAlpha=" + SMOOTHING_ALPHA +
                ", minBytes=" + MIN_BYTES_FOR_STATISTICS +
                ", windowSize=" + BackendServer.WINDOW_SIZE_MS + "ms" +
                "}";
    }
}
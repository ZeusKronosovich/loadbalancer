package my.workers.loadbalancer.health;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import my.workers.loadbalancer.model.BackendServer;

public class HealthChecker {
    private static final Logger log = LoggerFactory.getLogger(HealthChecker.class);
    private final CopyOnWriteArrayList<BackendServer> backends;
    private final long intervalMillis;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean running = false;
    
    private final ConcurrentHashMap<String, Boolean> lastKnownStatus = new ConcurrentHashMap<>();
    private final AtomicLong checkCounter = new AtomicLong(0);

    public HealthChecker(List<BackendServer> backends, long intervalMillis) {
        if (backends instanceof CopyOnWriteArrayList) {
            this.backends = (CopyOnWriteArrayList<BackendServer>) backends;
        } else {
            this.backends = new CopyOnWriteArrayList<>(backends);
        }
        this.intervalMillis = intervalMillis;
        log.info("HealthChecker created with {} backends", this.backends.size());
    }

    public void start() {
        running = true;
        scheduler.scheduleAtFixedRate(this::checkAll, 0, intervalMillis, TimeUnit.MILLISECONDS);
        log.info("HealthChecker started with interval {} ms", intervalMillis);
    }

    public void stop() {
        running = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("HealthChecker stopped");
    }

    private void checkAll() {
        if (!running) {
            return;
        }
        
        long checkId = checkCounter.incrementAndGet();
        
        List<BackendServer> currentBackends = List.copyOf(backends);
        
        if (currentBackends.isEmpty()) {
            log.debug("No backends to check");
            return;
        }
        
        if (log.isDebugEnabled()) {
            StringBuilder sb = new StringBuilder("Checking backends: ");
            for (BackendServer s : currentBackends) {
                sb.append(s.getHost()).append(":").append(s.getPort()).append(" ");
            }
            log.debug(sb.toString());
        }
        
        log.debug("Health check #{}: checking {} backends", checkId, currentBackends.size());
        
        for (BackendServer server : currentBackends) {
            String key = server.getHost() + ":" + server.getPort();
            boolean wasAlive = server.isAlive();
            boolean reachable = isReachable(server.getHost(), server.getPort(), 2000);
            
            server.setAlive(reachable);
            
            Boolean lastStatus = lastKnownStatus.get(key);
            
            if (lastStatus == null || lastStatus != reachable) {
                if (reachable) {
                    log.info("Backend {}:{} is UP (was: {})", server.getHost(), server.getPort(), wasAlive);
                    if (!wasAlive) {
                        server.resetResponseMetrics();
                        server.resetBandwidthMetrics();
                    }
                } else {
                    log.warn("Backend {}:{} is DOWN (was: {})", server.getHost(), server.getPort(), wasAlive);
                    server.resetResponseMetrics();
                    server.resetBandwidthMetrics();
                }
                lastKnownStatus.put(key, reachable);
            } else {
                if (log.isTraceEnabled()) {
                    log.trace("Backend {}:{} status unchanged: {}", server.getHost(), server.getPort(), reachable);
                }
            }
        }
        
        if (checkId % 5 == 0) {
            StringBuilder sb = new StringBuilder("Current backends status: ");
            for (BackendServer server : currentBackends) {
                sb.append(server.getHost()).append(":").append(server.getPort())
                  .append("=").append(server.isAlive() ? "UP" : "DOWN").append(" ");
            }
            log.info(sb.toString());
        }
    }

    private boolean isReachable(String host, int port, int timeoutMillis) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
    
    public boolean checkBackend(BackendServer backend) {
        if (backend == null) return false;
        String key = backend.getHost() + ":" + backend.getPort();
        boolean reachable = isReachable(backend.getHost(), backend.getPort(), 2000);
        boolean wasAlive = backend.isAlive();
        
        backend.setAlive(reachable);
        lastKnownStatus.put(key, reachable);
        
        if (reachable) {
            log.info("Manual check: Backend {}:{} is UP (was: {})", 
                backend.getHost(), backend.getPort(), wasAlive);
            if (!wasAlive) {
                backend.resetResponseMetrics();
                backend.resetBandwidthMetrics();
            }
        } else {
            log.info("Manual check: Backend {}:{} is DOWN (was: {})", 
                backend.getHost(), backend.getPort(), wasAlive);
        }
        
        return reachable;
    }
    
    public void refreshAll() {
        log.info("Forcing refresh of all backends status");
        lastKnownStatus.clear();
        checkAll();
    }
    
    public void forceCheck(String host, int port) {
        log.info("Force checking backend {}:{}", host, port);
        for (BackendServer backend : backends) {
            if (backend.getHost().equals(host) && backend.getPort() == port) {
                String key = host + ":" + port;
                lastKnownStatus.remove(key);
                checkBackend(backend);
                return;
            }
        }
        log.warn("Backend {}:{} not found", host, port);
    }
    
    public String getStatusReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("Total backends: ").append(backends.size()).append("\n");
        for (BackendServer backend : backends) {
            String key = backend.getHost() + ":" + backend.getPort();
            Boolean lastStatus = lastKnownStatus.get(key);
            sb.append("  ").append(key).append(": alive=").append(backend.isAlive())
              .append(", lastCheck=").append(lastStatus != null ? lastStatus : "never")
              .append("\n");
        }
        return sb.toString();
    }
}
package my.workers.loadbalancer.model;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class BackendServer {
    private final String host;
    private final int port;
    private volatile boolean alive = true;
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    
    private final AtomicLong totalRequestsProcessed = new AtomicLong(0);
    
    private final AtomicLong totalResponseTime = new AtomicLong(0);
    private final AtomicLong totalLrtRequests = new AtomicLong(0);
    private volatile double averageResponseTime = 0.0;
    private static final double EWMA_ALPHA = 0.3;
    
    private final AtomicLong totalBytesTransferred = new AtomicLong(0);
    private final AtomicLong bytesInCurrentWindow = new AtomicLong(0);
    private volatile long lastWindowReset = System.currentTimeMillis();
    public static final long WINDOW_SIZE_MS = 5000;
    private volatile double currentBandwidth = 0.0;

    public BackendServer(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public String getHost() { return host; }
    public int getPort() { return port; }

    public boolean isAlive() { return alive; }
    public void setAlive(boolean alive) { this.alive = alive; }

    public int getActiveConnections() {
        return activeConnections.get();
    }
    public void incrementActiveConnections() {
        activeConnections.incrementAndGet();
    }
    public void decrementActiveConnections() {
        activeConnections.decrementAndGet();
    }

    public void incrementTotalRequests() {
        totalRequestsProcessed.incrementAndGet();
    }
    
    public long getTotalRequests() {
        return totalRequestsProcessed.get();
    }

    public void recordResponseTime(long responseTimeMs) {
        if (responseTimeMs <= 0 || responseTimeMs > 60000) {
            return;
        }
        
        totalResponseTime.addAndGet(responseTimeMs);
        long count = totalLrtRequests.incrementAndGet();
        
        double newAvg;
        if (count == 1) {
            newAvg = responseTimeMs;
        } else {
            newAvg = EWMA_ALPHA * responseTimeMs + (1 - EWMA_ALPHA) * averageResponseTime;
        }
        averageResponseTime = newAvg;
    }
    
    public void updateAverageResponseTime(double newAverage) {
        if (newAverage > 0) {
            this.averageResponseTime = newAverage;
            totalLrtRequests.incrementAndGet();
        }
    }
    
    public double getAverageResponseTime() {
        return averageResponseTime;
    }
    
    public void resetResponseMetrics() {
        totalResponseTime.set(0);
        totalLrtRequests.set(0);
        averageResponseTime = 0.0;
    }

    public void recordBytesTransferred(long bytes) {
        if (bytes <= 0) return;
        
        totalBytesTransferred.addAndGet(bytes);
        bytesInCurrentWindow.addAndGet(bytes);
        updateBandwidth();
    }
    
    private void updateBandwidth() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastWindowReset;
        
        if (elapsed >= WINDOW_SIZE_MS) {
            double bytesPerSecond = (double) bytesInCurrentWindow.get() / (elapsed / 1000.0);
            currentBandwidth = bytesPerSecond;
            bytesInCurrentWindow.set(0);
            lastWindowReset = now;
        }
    }
    
    public double getCurrentBandwidth() {
        updateBandwidth();
        return currentBandwidth;
    }
    
    public long getTotalBytesTransferred() {
        return totalBytesTransferred.get();
    }
    
    public long getBytesInCurrentWindow() {
        return bytesInCurrentWindow.get();
    }
    
    public void resetBandwidthMetrics() {
        totalBytesTransferred.set(0);
        bytesInCurrentWindow.set(0);
        currentBandwidth = 0.0;
        lastWindowReset = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return host + ":" + port + " [alive=" + alive + ", conns=" + activeConnections.get() 
                + ", totalReq=" + totalRequestsProcessed.get()
                + ", avgRT=" + String.format("%.2fms", averageResponseTime)
                + ", bw=" + String.format("%.2f", currentBandwidth) + " B/s]";
    }
}
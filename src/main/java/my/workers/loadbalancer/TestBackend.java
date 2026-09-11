package my.workers.loadbalancer;

import java.net.InetSocketAddress;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.net.httpserver.HttpServer;

public class TestBackend {
    private static final Random random = new Random();
    private static final AtomicLong requestCounter = new AtomicLong(0);
    
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java TestBackend <port> [responseSize] [delayMs] [errorRate]");
            System.err.println("  port         - port to listen on (required)");
            System.err.println("  responseSize - response size in bytes (default: 1024)");
            System.err.println("  delayMs      - artificial delay in ms (default: 0)");
            System.err.println("  errorRate    - error rate 0.0-1.0 (default: 0.0)");
            System.err.println("Example: java TestBackend 8081 2048 100 0.1");
            System.exit(1);
        }
        
        int port;
        try {
            port = Integer.parseInt(args[0]);
            if (port < 1 || port > 65535) {
                System.err.println("Port must be between 1 and 65535");
                System.exit(1);
            }
        } catch (NumberFormatException e) {
            System.err.println("Invalid port number");
            System.exit(1);
            return;
        }
        
        int responseSize = args.length > 1 ? Integer.parseInt(args[1]) : 1024;
        int delayMs = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        double errorRate = args.length > 3 ? Double.parseDouble(args[3]) : 0.0;
        
        if (responseSize < 1) responseSize = 1;
        if (responseSize > 1024 * 1024 * 10) responseSize = 1024 * 1024 * 10;
        if (delayMs < 0) delayMs = 0;
        if (delayMs > 60000) delayMs = 60000;
        if (errorRate < 0) errorRate = 0;
        if (errorRate > 1) errorRate = 1;
        
        final int finalPort = port;
        final int finalResponseSize = responseSize;
        final int finalDelayMs = delayMs;
        final double finalErrorRate = errorRate;
        
        System.out.println("Backend Configuration");
        System.out.println("Port: " + finalPort);
        System.out.println("Response Size: " + finalResponseSize + " bytes");
        System.out.println("Delay: " + finalDelayMs + " ms");
        System.out.println("Error Rate: " + (finalErrorRate * 100) + "%");
        
        final byte[] responseBody = generateRandomBytes(finalResponseSize);
        
        HttpServer server = HttpServer.create(new InetSocketAddress(finalPort), 0);
        
        server.createContext("/", exchange -> {
            long requestId = requestCounter.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String clientInfo = exchange.getRemoteAddress().toString();
            
            if (finalDelayMs > 0) {
                try {
                    Thread.sleep(finalDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            if (finalErrorRate > 0 && random.nextDouble() < finalErrorRate) {
                int errorCode = random.nextBoolean() ? 500 : 503;
                String errorMsg = "Simulated error (" + errorCode + ") for request #" + requestId;
                exchange.sendResponseHeaders(errorCode, errorMsg.length());
                exchange.getResponseBody().write(errorMsg.getBytes());
                exchange.close();
                System.err.println("[" + finalPort + "] ERROR: " + errorMsg + " from " + clientInfo);
                return;
            }
            
            String responseHeader = String.format(
                "Backend: %d | Request: %d | Path: %s | Method: %s | Size: %d bytes\n",
                finalPort, requestId, path, method, finalResponseSize
            );
            
            byte[] headerBytes = responseHeader.getBytes();
            byte[] fullResponse = new byte[headerBytes.length + responseBody.length];
            System.arraycopy(headerBytes, 0, fullResponse, 0, headerBytes.length);
            System.arraycopy(responseBody, 0, fullResponse, headerBytes.length, responseBody.length);
            
            exchange.sendResponseHeaders(200, fullResponse.length);
            exchange.getResponseBody().write(fullResponse);
            exchange.close();
            
            if (requestId % 100 == 0) {
                System.out.printf("[%d] Request #%d from %s (size: %d bytes, delay: %dms)%n",
                    finalPort, requestId, clientInfo, finalResponseSize, finalDelayMs);
            }
        });
        
        server.createContext("/health", exchange -> {
            String response = "OK";
            exchange.sendResponseHeaders(200, response.length());
            exchange.getResponseBody().write(response.getBytes());
            exchange.close();
        });
        
        server.createContext("/stats", exchange -> {
            String stats = String.format(
                "{\"port\":%d,\"requests\":%d,\"responseSize\":%d,\"delayMs\":%d,\"errorRate\":%.2f}",
                finalPort, requestCounter.get(), finalResponseSize, finalDelayMs, finalErrorRate
            );
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, stats.length());
            exchange.getResponseBody().write(stats.getBytes());
            exchange.close();
        });
        
        server.createContext("/random", exchange -> {
            String sizeParam = exchange.getRequestURI().getQuery();
            int size = finalResponseSize;
            if (sizeParam != null && sizeParam.startsWith("size=")) {
                try {
                    size = Integer.parseInt(sizeParam.substring(5));
                    if (size < 1) size = 1;
                    if (size > 1024 * 1024) size = 1024 * 1024;
                } catch (NumberFormatException e) {
                }
            }
            
            final int finalSize = size;
            byte[] data = generateRandomBytes(finalSize);
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
        
        server.setExecutor(null);
        server.start();
        
        System.out.println("Backend started on port " + finalPort);
        System.out.println("Endpoints:");
        System.out.println("  GET  /         - main endpoint");
        System.out.println("  GET  /health   - health check");
        System.out.println("  GET  /stats    - statistics");
        System.out.println("  GET  /random   - random data (size=bytes)");
        System.out.println("Press Enter to stop");
        System.in.read();
        server.stop(0);
    }
    
    private static byte[] generateRandomBytes(int size) {
        byte[] bytes = new byte[size];
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()_+-=[]{}|;:,.<>?";
        byte[] charBytes = chars.getBytes();
        
        for (int i = 0; i < size; i++) {
            bytes[i] = charBytes[random.nextInt(charBytes.length)];
        }
        return bytes;
    }
}
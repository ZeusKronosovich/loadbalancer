package my.workers.loadbalancer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.timeout.ReadTimeoutHandler;
import my.workers.loadbalancer.health.HealthChecker;
import my.workers.loadbalancer.model.BackendServer;
import my.workers.loadbalancer.strategy.LeastBandwidthStrategy;
import my.workers.loadbalancer.strategy.LeastResponseTimeStrategy;
import my.workers.loadbalancer.strategy.LoadBalancingStrategy;
import my.workers.loadbalancer.strategy.WeightedRoundRobinStrategy;

public class LoadBalancer {
    private static final Logger log = LoggerFactory.getLogger(LoadBalancer.class);

    private final int localPort;
    private final CopyOnWriteArrayList<BackendServer> backends;
    
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    private final Map<String, AtomicLong> requestsPerBackend = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> errorsPerBackend = new ConcurrentHashMap<>();
    private final long startTime = System.currentTimeMillis();
    private final AtomicLong activeConnectionsTotal = new AtomicLong(0);
    
    private final AtomicLong lastRequestCount = new AtomicLong(0);
    private final AtomicLong lastRpsTimestamp = new AtomicLong(System.currentTimeMillis());
    private final AtomicReference<Double> currentRps = new AtomicReference<>(0.0);

    private final AtomicReference<LoadBalancingStrategy> strategyRef;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private volatile boolean shutdownInitiated = false;
    
    private HealthChecker healthChecker;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public LoadBalancer(int localPort, List<BackendServer> initialBackends, LoadBalancingStrategy initialStrategy) {
        this.localPort = localPort;
        if (initialBackends instanceof CopyOnWriteArrayList) {
            this.backends = (CopyOnWriteArrayList<BackendServer>) initialBackends;
        } else {
            this.backends = new CopyOnWriteArrayList<>(initialBackends);
        }
        this.strategyRef = new AtomicReference<>(initialStrategy);
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();
    }

    public void setHealthChecker(HealthChecker healthChecker) {
        this.healthChecker = healthChecker;
    }

    public void setStrategy(LoadBalancingStrategy newStrategy) {
        LoadBalancingStrategy oldStrategy = strategyRef.getAndSet(newStrategy);
        log.info("Strategy changed from {} to {}", 
            oldStrategy != null ? oldStrategy.getClass().getSimpleName() : "null",
            newStrategy.getClass().getSimpleName()
        );
    }

    public LoadBalancingStrategy getStrategy() {
        return strategyRef.get();
    }

    public List<BackendServer> getBackends() {
        return backends;
    }

    public Map<String, Integer> getBackendWeights() {
        LoadBalancingStrategy strategy = strategyRef.get();
        if (strategy instanceof WeightedRoundRobinStrategy weightedStrategy) {
            return weightedStrategy.getAllWeights();
        }
        return new HashMap<>();
    }
  
    public void addBackend(String host, int port) {
        boolean exists = backends.stream()
                .anyMatch(b -> b.getHost().equals(host) && b.getPort() == port);
        if (exists) {
            log.warn("Backend {}:{} already exists", host, port);
            return;
        }
        BackendServer newBackend = new BackendServer(host, port);
        
        boolean reachable;
        if (healthChecker != null) {
            reachable = healthChecker.checkBackend(newBackend);
        } else {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 2000);
                reachable = true;
            } catch (IOException e) {
                reachable = false;
            }
        }
        
        newBackend.setAlive(reachable);
        backends.add(newBackend);
        
        if (reachable) {
            log.info("Added backend: {}:{} (status: UP)", host, port);
        } else {
            log.warn("Added backend: {}:{} (status: DOWN - not responding)", host, port);
        }
        
        if (healthChecker != null) {
            scheduler.schedule(healthChecker::refreshAll, 100, TimeUnit.MILLISECONDS);
        }
    }

    public void removeBackend(String host, int port) {
        BackendServer removed = backends.stream()
                .filter(b -> b.getHost().equals(host) && b.getPort() == port)
                .findFirst()
                .orElse(null);
        
        if (removed != null) {
            backends.remove(removed);
            log.info("Removed backend: {}:{}", host, port);
        } else {
            log.warn("Backend {}:{} not found", host, port);
        }
    }
    
    public void refreshBackendStatus() {
        if (healthChecker != null) {
            healthChecker.refreshAll();
        }
    }
    
    public void checkBackendStatus(String host, int port) {
        if (healthChecker != null) {
            healthChecker.forceCheck(host, port);
        }
    }
    
    public void forceRefreshAllBackends() {
        if (healthChecker != null) {
            healthChecker.refreshAll();
        }
    }
    
    public String getHealthStatusReport() {
        if (healthChecker != null) {
            return healthChecker.getStatusReport();
        }
        return "HealthChecker not initialized";
    }

    public long getTotalRequests() { return totalRequests.get(); }
    public long getSuccessfulRequests() { return successfulRequests.get(); }
    public long getFailedRequests() { return failedRequests.get(); }
    public long getStartTime() { return startTime; }
    public Map<String, AtomicLong> getRequestsPerBackend() { return requestsPerBackend; }
    public Map<String, AtomicLong> getErrorsPerBackend() { return errorsPerBackend; }
    public long getActiveConnectionsTotal() { return activeConnectionsTotal.get(); }
    
    public double getRps() {
        long now = System.currentTimeMillis();
        long currentTotal = totalRequests.get();
        long lastTotal = lastRequestCount.getAndSet(currentTotal);
        long lastTime = lastRpsTimestamp.getAndSet(now);

        long timeDiff = now - lastTime;
        if (timeDiff > 0) {
            double rps = (double) (currentTotal - lastTotal) / (timeDiff / 1000.0);
            currentRps.set(rps);
            return rps;
        }
        return currentRps.get();
    }

    public void start() throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(65536));
                        ch.pipeline().addLast(new FrontendHandler());
                    }
                })
                .bind(localPort)
                .sync();
        log.info("LoadBalancer started on port {}", localPort);
    }

    public void shutdownGracefully() throws InterruptedException {
        if (shutdownInitiated) {
            return;
        }
        shutdownInitiated = true;
        log.info("Shutdown initiated");

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        bossGroup.shutdownGracefully().sync();
        log.info("Boss group stopped");

        int waitTime = 0;
        int maxWaitSeconds = 30;
        while (activeConnectionsTotal.get() > 0 && waitTime < maxWaitSeconds) {
            log.info("Waiting for {} active connections to finish... ({}s)",
                    activeConnectionsTotal.get(), waitTime);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            waitTime++;
        }

        if (activeConnectionsTotal.get() > 0) {
            log.warn("Forcing shutdown with {} active connections remaining", activeConnectionsTotal.get());
        }

        workerGroup.shutdownGracefully(2, 5, TimeUnit.SECONDS).sync();
        log.info("Worker group stopped");
        log.info("Shutdown complete");
    }

    public void shutdown() {
        if (shutdownInitiated) {
            return;
        }
        shutdownInitiated = true;
        scheduler.shutdown();
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    private class RequestContext {
        final BackendServer backend;
        final FullHttpRequest request;
        final ChannelHandlerContext clientCtx;
        final LoadBalancingStrategy strategy;
        final String backendKey;
        final long requestId;
        boolean completed = false;
        long startTime;

        RequestContext(BackendServer backend, FullHttpRequest request, 
                       ChannelHandlerContext clientCtx, LoadBalancingStrategy strategy) {
            this.backend = backend;
            this.request = request;
            this.clientCtx = clientCtx;
            this.strategy = strategy;
            this.backendKey = backend.getHost() + ":" + backend.getPort();
            this.requestId = totalRequests.incrementAndGet();
            this.startTime = System.currentTimeMillis();
        }

        void cleanup(boolean success, int bytesTransferred) {
            if (completed) {
                return;
            }
            completed = true;

            long duration = System.currentTimeMillis() - startTime;

            backend.decrementActiveConnections();
            activeConnectionsTotal.decrementAndGet();
            
            if (success) {
                successfulRequests.incrementAndGet();
                backend.incrementTotalRequests();
            } else {
                failedRequests.incrementAndGet();
                errorsPerBackend.computeIfAbsent(backendKey, k -> new AtomicLong())
                                 .incrementAndGet();
            }

            if (strategy instanceof LeastBandwidthStrategy lbStrategy && success) {
                lbStrategy.recordBytesTransferred(backend, bytesTransferred);
            }
            
            if (strategy instanceof LeastResponseTimeStrategy lrtStrategy) {
                lrtStrategy.recordRequestEnd(backend, success);
            }
            
            request.release();
            
            if (log.isTraceEnabled()) {
                log.trace("Request #{} completed: success={}, duration={}ms, backend={}:{}",
                    requestId, success, duration, backend.getHost(), backend.getPort());
            }
        }

        void handleError(String message, Throwable cause) {
            if (completed) {
                return;
            }
            
            log.error("Request #{} error for {}:{}: {}", 
                requestId, backend.getHost(), backend.getPort(), message, cause);
            
            cleanup(false, 0);

            if (clientCtx.channel().isActive()) {
                String errorMsg = "Backend error: " + message + "\n";
                FullHttpResponse errorResponse = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.BAD_GATEWAY,
                    Unpooled.copiedBuffer(errorMsg.getBytes())
                );
                errorResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                errorResponse.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, errorMsg.length());
                
                ChannelFuture future = clientCtx.writeAndFlush(errorResponse);
                future.addListener(ChannelFutureListener.CLOSE);
            }
        }
    }

    private class BackendHandler extends ChannelInboundHandlerAdapter {
        private final RequestContext ctx;
        private boolean responseReceived = false;
        
        BackendHandler(RequestContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void channelRead(ChannelHandlerContext backendCtx, Object msg) {
            if (msg instanceof FullHttpResponse response) {
                responseReceived = true;
                final int contentLength = response.content().readableBytes();
                
                ChannelFuture writeFuture = ctx.clientCtx.writeAndFlush(
                    response.retainedDuplicate()
                );
                
                writeFuture.addListener((ChannelFutureListener) future -> {
                    try {

                        ctx.cleanup(true, contentLength);
                        
                        backendCtx.close();
                        
                        boolean shouldClose = !HttpUtil.isKeepAlive(ctx.request) ||
                                             ctx.request.headers().contains("Connection", "close", true);
                        
                        if (shouldClose) {
                            ctx.clientCtx.close();
                        }
                        
                        response.release();
                        
                    } catch (Exception e) {
                        log.error("Error in response handler for request #{}", ctx.requestId, e);
                    }
                });
            } else {

                ctx.clientCtx.writeAndFlush(msg);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext backendCtx) {
            if (!responseReceived && !ctx.completed) {

                ctx.handleError("Backend closed connection without response", null);
            }
            backendCtx.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext backendCtx, Throwable cause) {
            if (!ctx.completed) {
                ctx.handleError("Backend error: " + cause.getMessage(), cause);
            }
            backendCtx.close();
            
            if (ctx.clientCtx.channel().isActive()) {
                ctx.clientCtx.close();
            }
        }
    }

    private class FrontendHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (shutdownInitiated) {
                ctx.writeAndFlush(Unpooled.copiedBuffer("Shutting down\n".getBytes()))
                    .addListener(ChannelFutureListener.CLOSE);
                return;
            }

            if (!(msg instanceof FullHttpRequest)) {
                handleRawRequest(ctx, msg);
                return;
            }

            FullHttpRequest request = (FullHttpRequest) msg;
            
            try {

                BackendServer targetBackend = selectBackend(ctx, request);
                
                if (targetBackend == null) {
                    handleNoBackendAvailable(ctx, request);
                    return;
                }

                final RequestContext requestContext = new RequestContext(
                    targetBackend,
                    request,
                    ctx,
                    strategyRef.get()
                );
                
                targetBackend.incrementActiveConnections();
                activeConnectionsTotal.incrementAndGet();
                
                LoadBalancingStrategy strategy = strategyRef.get();
                if (strategy instanceof LeastResponseTimeStrategy lrtStrategy) {
                    lrtStrategy.recordRequestStart(targetBackend);
                }
                
                String backendKey = targetBackend.getHost() + ":" + targetBackend.getPort();
                requestsPerBackend.computeIfAbsent(backendKey, k -> new AtomicLong()).incrementAndGet();

                Bootstrap bootstrap = new Bootstrap();
                bootstrap.group(workerGroup)
                        .channel(io.netty.channel.socket.nio.NioSocketChannel.class)
                        .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                ch.pipeline().addLast(new HttpClientCodec());
                                ch.pipeline().addLast(new HttpObjectAggregator(65536));
                                ch.pipeline().addLast(new ReadTimeoutHandler(30, TimeUnit.SECONDS));
                                ch.pipeline().addLast(new BackendHandler(requestContext));
                            }
                        });

                ChannelFuture future = bootstrap.connect(targetBackend.getHost(), targetBackend.getPort());
                
                future.addListener((ChannelFutureListener) f -> {
                    if (f.isSuccess()) {
                        log.debug("Connected to backend {}:{} for request #{}", 
                            targetBackend.getHost(), targetBackend.getPort(), requestContext.requestId);
                        
                        FullHttpRequest forwardedRequest = createForwardedRequest(request, targetBackend);
                        
                        f.channel().writeAndFlush(forwardedRequest);
                    } else {
                        requestContext.handleError(
                            "Failed to connect to backend: " + f.cause().getMessage(),
                            f.cause()
                        );
                    }
                });
                
            } catch (Exception e) {
                log.error("Error processing request", e);
                request.release();
                
                FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    Unpooled.copiedBuffer(("Internal error: " + e.getMessage() + "\n").getBytes())
                );
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            }
        }
        
        private BackendServer selectBackend(ChannelHandlerContext ctx, FullHttpRequest request) {
            String fbackHeader = request.headers().get("fback");
            BackendServer targetBackend = null;
            
            if (fbackHeader != null && !fbackHeader.isEmpty()) {
                try {
                    int targetPort = Integer.parseInt(fbackHeader.trim());
                    for (BackendServer backend : backends) {
                        if (backend.getPort() == targetPort && backend.isAlive()) {
                            targetBackend = backend;
                            log.debug("Request forced to backend {}:{} via fback header", 
                                backend.getHost(), backend.getPort());
                            break;
                        }
                    }
                    if (targetBackend == null) {
                        log.warn("fback header specified port {} but no alive backend found", targetPort);
                    }
                } catch (NumberFormatException e) {
                    log.warn("Invalid fback header value: {}", fbackHeader);
                }
            }
            
            if (targetBackend == null) {
                Object key = ctx.channel().remoteAddress();
                LoadBalancingStrategy strategy = strategyRef.get();
                targetBackend = strategy.selectBackend(backends, key);
            }
            
            return targetBackend;
        }
        
        private void handleNoBackendAvailable(ChannelHandlerContext ctx, FullHttpRequest request) {
            totalRequests.incrementAndGet();
            failedRequests.incrementAndGet();
            log.warn("No alive backend available for request");
            request.release();
            
            FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.SERVICE_UNAVAILABLE,
                Unpooled.copiedBuffer("No backends available\n".getBytes())
            );
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
        
        private FullHttpRequest createForwardedRequest(FullHttpRequest request, BackendServer backend) {
            ByteBuf content = request.content().retainedDuplicate();
            HttpMethod method = request.method();
            String requestUri = request.uri();
            HttpHeaders headers = request.headers();
            
            FullHttpRequest forwardedRequest = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                method,
                requestUri,
                content
            );
            
            forwardedRequest.headers().set(headers);
            forwardedRequest.headers().remove("fback");
            forwardedRequest.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
            forwardedRequest.headers().set(HttpHeaderNames.HOST, backend.getHost() + ":" + backend.getPort());
            
            return forwardedRequest;
        }

        private void handleRawRequest(ChannelHandlerContext ctx, Object msg) {
            Object key = ctx.channel().remoteAddress();
            LoadBalancingStrategy strategy = strategyRef.get();
            BackendServer backend = strategy.selectBackend(backends, key);

            if (backend == null) {
                totalRequests.incrementAndGet();
                failedRequests.incrementAndGet();
                log.warn("No alive backend available for raw request");
                ctx.writeAndFlush(Unpooled.copiedBuffer("No backends available\n".getBytes()))
                    .addListener(ChannelFutureListener.CLOSE);
                return;
            }

            final BackendServer selectedBackend = backend;
            
            totalRequests.incrementAndGet();
            String backendKey = selectedBackend.getHost() + ":" + selectedBackend.getPort();
            requestsPerBackend.computeIfAbsent(backendKey, k -> new AtomicLong()).incrementAndGet();
            
            selectedBackend.incrementTotalRequests();
            selectedBackend.incrementActiveConnections();
            activeConnectionsTotal.incrementAndGet();

            if (strategy instanceof LeastResponseTimeStrategy lrtStrategy) {
                lrtStrategy.recordRequestStart(selectedBackend);
            }

            final LoadBalancingStrategy currentStrategy = strategy;
            final String finalBackendKey = backendKey;

            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(workerGroup)
                    .channel(io.netty.channel.socket.nio.NioSocketChannel.class)
                    .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext backendCtx, Object backendMsg) {
                                    ctx.writeAndFlush(backendMsg);
                                    
                                    if (backendMsg instanceof ByteBuf byteBuf) {
                                        int readableBytes = byteBuf.readableBytes();
                                        if (readableBytes > 0 && currentStrategy instanceof LeastBandwidthStrategy lbStrategy) {
                                            lbStrategy.recordBytesTransferred(selectedBackend, readableBytes);
                                        }
                                        
                                        selectedBackend.decrementActiveConnections();
                                        activeConnectionsTotal.decrementAndGet();
                                        successfulRequests.incrementAndGet();
                                        backendCtx.close();
                                    }
                                    
                                    if (currentStrategy instanceof LeastResponseTimeStrategy lrtStrategy) {
                                        lrtStrategy.recordRequestEnd(selectedBackend, true);
                                    }
                                }

                                @Override
                                public void channelInactive(ChannelHandlerContext backendCtx) {
                                    selectedBackend.decrementActiveConnections();
                                    activeConnectionsTotal.decrementAndGet();
                                    successfulRequests.incrementAndGet();
                                    ctx.close();
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext backendCtx, Throwable cause) {
                                    log.error("Backend error for {}:{}: {}", 
                                        selectedBackend.getHost(), selectedBackend.getPort(), cause.getMessage());
                                    
                                    selectedBackend.decrementActiveConnections();
                                    activeConnectionsTotal.decrementAndGet();
                                    failedRequests.incrementAndGet();
                                    errorsPerBackend.computeIfAbsent(finalBackendKey, k -> new AtomicLong()).incrementAndGet();
                                    
                                    if (currentStrategy instanceof LeastResponseTimeStrategy lrtStrategy) {
                                        lrtStrategy.recordRequestEnd(selectedBackend, false);
                                    }
                                    
                                    backendCtx.close();
                                    ctx.writeAndFlush(Unpooled.copiedBuffer(
                                            ("Backend error: " + cause.getMessage() + "\n").getBytes()
                                    )).addListener(ChannelFutureListener.CLOSE);
                                }
                            });
                        }
                    });

            ChannelFuture future = bootstrap.connect(selectedBackend.getHost(), selectedBackend.getPort());
            future.addListener((ChannelFutureListener) f -> {
                if (f.isSuccess()) {
                    log.debug("Connected to backend {}:{} for raw request", selectedBackend.getHost(), selectedBackend.getPort());
                    f.channel().writeAndFlush(msg);
                } else {
                    log.error("Failed to connect to backend {}:{}", selectedBackend.getHost(), selectedBackend.getPort());
                    selectedBackend.decrementActiveConnections();
                    activeConnectionsTotal.decrementAndGet();
                    failedRequests.incrementAndGet();
                    errorsPerBackend.computeIfAbsent(finalBackendKey, k -> new AtomicLong()).incrementAndGet();
                    
                    if (currentStrategy instanceof LeastResponseTimeStrategy lrtStrategy) {
                        lrtStrategy.recordRequestEnd(selectedBackend, false);
                    }
                    
                    ctx.writeAndFlush(Unpooled.copiedBuffer(
                            ("Failed to connect to backend " + selectedBackend.getPort() + "\n").getBytes()
                    )).addListener(ChannelFutureListener.CLOSE);
                }
            });
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Frontend error: {}", cause.getMessage());
            if (ctx.channel().isActive()) {
                ctx.writeAndFlush(Unpooled.copiedBuffer(
                        ("Frontend error: " + cause.getMessage() + "\n").getBytes()
                )).addListener(ChannelFutureListener.CLOSE);
            }
        }
    }
}
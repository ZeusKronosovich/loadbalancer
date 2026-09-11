package my.workers.loadbalancer.api;

import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.GlobalEventExecutor;
import my.workers.loadbalancer.LoadBalancer;
import my.workers.loadbalancer.model.BackendServer;
import my.workers.loadbalancer.strategy.ConsistentHashingStrategy;
import my.workers.loadbalancer.strategy.LeastBandwidthStrategy;
import my.workers.loadbalancer.strategy.LeastConnectionsStrategy;
import my.workers.loadbalancer.strategy.LeastResponseTimeStrategy;
import my.workers.loadbalancer.strategy.LoadBalancingStrategy;
import my.workers.loadbalancer.strategy.RoundRobinStrategy;
import my.workers.loadbalancer.strategy.WeightedRoundRobinStrategy;

public class HttpApiServer {
    private static final Logger log = LoggerFactory.getLogger(HttpApiServer.class);
    private final int port;
    private final LoadBalancer loadBalancer;
    private final Gson gson = new Gson();
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    private final ChannelGroup wsClients = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean running = true;

    public HttpApiServer(int port, LoadBalancer loadBalancer) {
        this.port = port;
        this.loadBalancer = loadBalancer;
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new HttpObjectAggregator(65536));
                        pipeline.addLast(new WebSocketServerCompressionHandler());
                        pipeline.addLast(new ApiHandler());
                    }
                })
                .bind(port)
                .sync();

        startWebSocketBroadcast();
        log.info("REST API and WebSocket (ws://localhost:{}/ws) started on port {}", port, port);
    }

    private void startWebSocketBroadcast() {
        scheduler.scheduleAtFixedRate(() -> {
            if (!running || wsClients.isEmpty()) return;
            try {
                Map<String, Object> update = new LinkedHashMap<>();
                update.put("timestamp", System.currentTimeMillis());

                String strategyName = loadBalancer.getStrategy().getClass().getSimpleName()
                        .replace("Strategy", "").toLowerCase();
                update.put("strategy", strategyName);

                Map<String, Integer> weights = loadBalancer.getBackendWeights();
                
                List<Map<String, Object>> backendsList = loadBalancer.getBackends().stream()
                        .map(b -> {
                            Map<String, Object> map = new HashMap<>();
                            map.put("host", b.getHost());
                            map.put("port", b.getPort());
                            map.put("alive", b.isAlive());
                            map.put("activeConnections", b.getActiveConnections());
                            
                            String key = b.getHost() + ":" + b.getPort();
                            map.put("weight", weights.getOrDefault(key, 1));
                            
                            map.put("averageResponseTime", b.getAverageResponseTime());
                            map.put("totalRequests", b.getTotalRequests());
                            
                            map.put("currentBandwidth", Math.round(b.getCurrentBandwidth()));
                            map.put("totalBytesTransferred", b.getTotalBytesTransferred());
                            map.put("bandwidthFormatted", formatBandwidth(b.getCurrentBandwidth()));
                            
                            return map;
                        })
                        .collect(Collectors.toList());
                update.put("backends", backendsList);

                Map<String, Object> stats = new LinkedHashMap<>();
                long total = loadBalancer.getTotalRequests();
                long success = loadBalancer.getSuccessfulRequests();
                long failed = loadBalancer.getFailedRequests();
                stats.put("totalRequests", total);
                stats.put("successfulRequests", success);
                stats.put("failedRequests", failed);
                stats.put("successRate", total > 0
                        ? String.format("%.2f", (double) success / total * 100)
                        : "0");
                stats.put("activeConnectionsTotal", loadBalancer.getActiveConnectionsTotal());
                stats.put("uptimeSeconds", (System.currentTimeMillis() - loadBalancer.getStartTime()) / 1000);
                stats.put("rps", Math.round(loadBalancer.getRps() * 10) / 10.0);
                
                update.put("stats", stats);

                String json = gson.toJson(update);
                wsClients.writeAndFlush(new TextWebSocketFrame(json));
            } catch (Exception e) {
                log.error("Error broadcasting WebSocket update", e);
            }
        }, 0, 500, TimeUnit.MILLISECONDS);
    }
    
    private String formatBandwidth(double bytesPerSecond) {
        if (bytesPerSecond < 1024) {
            return String.format("%.0f B/s", bytesPerSecond);
        } else if (bytesPerSecond < 1024 * 1024) {
            return String.format("%.1f KB/s", bytesPerSecond / 1024);
        } else {
            return String.format("%.1f MB/s", bytesPerSecond / (1024 * 1024));
        }
    }

    public void shutdown() {
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
        
        for (Channel ch : wsClients) {
            if (ch.isOpen()) {
                ch.writeAndFlush(new CloseWebSocketFrame())
                    .addListener(ChannelFutureListener.CLOSE);
            }
        }
        
        wsClients.close().awaitUninterruptibly();
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }

    private class ApiHandler extends SimpleChannelInboundHandler<Object> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof FullHttpRequest request) {
                handleHttpRequest(ctx, request);
            } else if (msg instanceof WebSocketFrame frame) {
                handleWebSocketFrame(ctx, frame);
            }
        }

        private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
            String uri = request.uri().split("\\?")[0];
            HttpMethod method = request.method();

            if (uri.equals("/ws")) {
                handleWebSocketUpgrade(ctx, request);
                return;
            }

            if (method.equals(HttpMethod.OPTIONS)) {
                sendResponse(ctx, request, OK, "".getBytes());
                return;
            }

            if (uri.startsWith("/api/")) {
                handleApi(ctx, request, uri, method);
                return;
            }

            serveStatic(ctx, request, uri);
        }

        private void handleWebSocketUpgrade(ChannelHandlerContext ctx, FullHttpRequest request) {
            try {
                log.info("WebSocket upgrade request from {}", ctx.channel().remoteAddress());

                WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                        "ws://localhost:" + port + "/ws",
                        null,
                        true,
                        65536
                );
                WebSocketServerHandshaker handshaker = wsFactory.newHandshaker(request);

                if (handshaker == null) {
                    log.warn("Unsupported WebSocket version");
                    WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
                    return;
                }

                handshaker.handshake(ctx.channel(), request)
                        .addListener((ChannelFutureListener) future -> {
                            if (future.isSuccess()) {
                                log.info("WebSocket client connected: {}", ctx.channel().remoteAddress());
                                wsClients.add(ctx.channel());

                                Map<String, Object> welcome = new HashMap<>();
                                welcome.put("type", "welcome");
                                welcome.put("message", "Connected to Load Balancer WebSocket");
                                welcome.put("timestamp", System.currentTimeMillis());
                                ctx.writeAndFlush(new TextWebSocketFrame(gson.toJson(welcome)));

                                ctx.channel().closeFuture().addListener(future2 -> {
                                    wsClients.remove(ctx.channel());
                                    log.info("WebSocket client disconnected: {}", ctx.channel().remoteAddress());
                                });
                            } else {
                                log.warn("WebSocket handshake failed for {}", ctx.channel().remoteAddress());
                            }
                        });

            } catch (Exception e) {
                log.error("WebSocket upgrade error", e);
                ctx.close();
            }
        }

        private void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (frame instanceof CloseWebSocketFrame) {
                log.info("WebSocket close frame received");
                wsClients.remove(ctx.channel());
                ctx.close();
                return;
            }

            if (frame instanceof PingWebSocketFrame) {
                ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
                return;
            }

            if (frame instanceof PongWebSocketFrame) {
                return;
            }

            if (frame instanceof TextWebSocketFrame textFrame) {
                String text = textFrame.text();
                log.debug("WebSocket message: {}", text);

                try {
                    JsonObject json = JsonParser.parseString(text).getAsJsonObject();
                    String command = json.get("command").getAsString();

                    switch (command) {
                        case "getStrategy" -> handleGetStrategy(ctx, null);
                        case "changeStrategy" -> {
                            String strategy = json.get("strategy").getAsString();
                            handlePostStrategy(ctx, null, strategy);
                        }
                        case "addBackend" -> {
                            String host = json.get("host").getAsString();
                            int port = json.get("port").getAsInt();
                            loadBalancer.addBackend(host, port);
                        }
                        case "removeBackend" -> {
                            String host = json.get("host").getAsString();
                            int port = json.get("port").getAsInt();
                            loadBalancer.removeBackend(host, port);
                        }
                        case "refreshBackends" -> loadBalancer.forceRefreshAllBackends();
                        default -> log.warn("Unknown WebSocket command: {}", command);
                    }
                } catch (JsonSyntaxException | IllegalStateException e) {
                    log.error("Error parsing WebSocket command", e);
                }
                return;
            }

            log.warn("Unsupported WebSocket frame type: {}", frame.getClass().getSimpleName());
        }

        private void handleApi(ChannelHandlerContext ctx, FullHttpRequest request, String uri, HttpMethod method) {
            if (method.equals(HttpMethod.GET) && uri.equals("/api/strategy")) {
                handleGetStrategy(ctx, request);
                return;
            }
            if (method.equals(HttpMethod.POST) && uri.equals("/api/strategy")) {
                handlePostStrategy(ctx, request, null);
                return;
            }
            if (method.equals(HttpMethod.GET) && uri.equals("/api/backends")) {
                handleGetBackends(ctx, request);
                return;
            }
            if (method.equals(HttpMethod.POST) && uri.equals("/api/backends")) {
                handleAddBackend(ctx, request);
                return;
            }
            if (method.equals(HttpMethod.DELETE) && uri.equals("/api/backends")) {
                handleRemoveBackend(ctx, request);
                return;
            }
            if (method.equals(HttpMethod.GET) && uri.equals("/api/stats")) {
                handleGetStats(ctx, request);
                return;
            }
            if (method.equals(HttpMethod.POST) && uri.equals("/api/weights")) {
                handleSetWeight(ctx, request);
                return;
            }
            if (method.equals(HttpMethod.POST) && uri.equals("/api/backends/refresh")) {
                handleRefreshBackends(ctx, request);
                return;
            }
            if (method.equals(HttpMethod.POST) && uri.equals("/api/backends/check")) {
                handleCheckBackend(ctx, request);
                return;
            }
            if (method.equals(HttpMethod.GET) && uri.equals("/api/backends/debug")) {
                handleBackendsDebug(ctx, request);
                return;
            }
            if (method.equals(HttpMethod.GET) && uri.equals("/api/health/debug")) {
                handleHealthDebug(ctx, request);
                return;
            }

            sendResponse(ctx, request, NOT_FOUND, "{\"error\":\"Not found\"}".getBytes());
        }

        private void handleGetStrategy(ChannelHandlerContext ctx, FullHttpRequest request) {
            LoadBalancingStrategy strategy = loadBalancer.getStrategy();
            String name = strategy.getClass().getSimpleName();
            String friendlyName = name.replace("Strategy", "").toLowerCase();
            String json = "{\"strategy\":\"" + friendlyName + "\"}";

            if (request != null) {
                sendResponse(ctx, request, OK, json.getBytes(CharsetUtil.UTF_8));
            } else {
                ctx.writeAndFlush(new TextWebSocketFrame(json));
            }
        }

        private void handlePostStrategy(ChannelHandlerContext ctx, FullHttpRequest request, String typeOverride) {
            String body;
            if (request != null) {
                ByteBuf content = request.content();
                int contentLength = content.readableBytes();
                if (contentLength > 1024 * 1024) {
                    String errorMsg = "{\"error\":\"Request body too large\"}";
                    sendResponse(ctx, request, BAD_REQUEST, errorMsg.getBytes(CharsetUtil.UTF_8));
                    return;
                }
                body = content.toString(CharsetUtil.UTF_8);
            } else {
                body = "{\"type\":\"" + typeOverride + "\"}";
            }

            try {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                String type = json.get("type").getAsString();

                LoadBalancingStrategy newStrategy;
                switch (type.toLowerCase()) {
                    case "round_robin" -> newStrategy = new RoundRobinStrategy();
                    case "least_connections" -> newStrategy = new LeastConnectionsStrategy();
                    case "consistent_hashing" -> newStrategy = new ConsistentHashingStrategy();
                    case "weighted_round_robin" -> newStrategy = new WeightedRoundRobinStrategy();
                    case "least_response_time" -> newStrategy = new LeastResponseTimeStrategy();
                    case "least_bandwidth" -> newStrategy = new LeastBandwidthStrategy();
                    default -> {
                        String errorMsg = "{\"error\":\"Unknown strategy: " + type + "\"}";
                        if (request != null) {
                            sendResponse(ctx, request, BAD_REQUEST, errorMsg.getBytes(CharsetUtil.UTF_8));
                        } else {
                            ctx.writeAndFlush(new TextWebSocketFrame(errorMsg));
                        }
                        return;
                    }
                }
                loadBalancer.setStrategy(newStrategy);
                String resp = "{\"status\":\"ok\", \"strategy\":\"" + type + "\"}";

                if (request != null) {
                    sendResponse(ctx, request, OK, resp.getBytes(CharsetUtil.UTF_8));
                } else {
                    ctx.writeAndFlush(new TextWebSocketFrame(resp));
                }
            } catch (JsonSyntaxException | IllegalStateException e) {
                log.error("Error parsing strategy", e);
                String errorMsg = "{\"error\":\"Invalid JSON: " + e.getMessage() + "\"}";
                if (request != null) {
                    sendResponse(ctx, request, BAD_REQUEST, errorMsg.getBytes(CharsetUtil.UTF_8));
                } else {
                    ctx.writeAndFlush(new TextWebSocketFrame(errorMsg));
                }
            }
        }

        private void handleGetBackends(ChannelHandlerContext ctx, FullHttpRequest request) {
            List<BackendServer> backends = loadBalancer.getBackends();
            Map<String, Integer> weights = loadBalancer.getBackendWeights();
            
            List<Map<String, Object>> list = backends.stream()
                    .map(b -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("host", b.getHost());
                        map.put("port", b.getPort());
                        map.put("alive", b.isAlive());
                        map.put("activeConnections", b.getActiveConnections());
                        
                        String key = b.getHost() + ":" + b.getPort();
                        map.put("weight", weights.getOrDefault(key, 1));
                        
                        map.put("averageResponseTime", b.getAverageResponseTime());
                        map.put("totalRequests", b.getTotalRequests());
                        
                        map.put("currentBandwidth", Math.round(b.getCurrentBandwidth()));
                        map.put("totalBytesTransferred", b.getTotalBytesTransferred());
                        map.put("bandwidthFormatted", formatBandwidth(b.getCurrentBandwidth()));
                        
                        return map;
                    })
                    .collect(Collectors.toList());
            String json = gson.toJson(Map.of("backends", list));

            if (request != null) {
                sendResponse(ctx, request, OK, json.getBytes(CharsetUtil.UTF_8));
            } else {
                ctx.writeAndFlush(new TextWebSocketFrame(json));
            }
        }

        private void handleGetStats(ChannelHandlerContext ctx, FullHttpRequest request) {
            Map<String, Object> stats = new LinkedHashMap<>();
            long total = loadBalancer.getTotalRequests();
            long success = loadBalancer.getSuccessfulRequests();
            long failed = loadBalancer.getFailedRequests();
            stats.put("totalRequests", total);
            stats.put("successfulRequests", success);
            stats.put("failedRequests", failed);
            stats.put("successRate", total > 0
                    ? String.format("%.2f%%", (double) success / total * 100)
                    : "0%");
            stats.put("uptimeSeconds", (System.currentTimeMillis() - loadBalancer.getStartTime()) / 1000);
            stats.put("activeConnectionsTotal", loadBalancer.getActiveConnectionsTotal());
            stats.put("rps", Math.round(loadBalancer.getRps() * 10) / 10.0);

            Map<String, Object> backendsStats = new LinkedHashMap<>();
            for (BackendServer b : loadBalancer.getBackends()) {
                String key = b.getHost() + ":" + b.getPort();
                Map<String, Object> bStats = new LinkedHashMap<>();
                bStats.put("alive", b.isAlive());
                bStats.put("activeConnections", b.getActiveConnections());
                bStats.put("requests", loadBalancer.getRequestsPerBackend()
                        .getOrDefault(key, new AtomicLong(0)).get());
                bStats.put("errors", loadBalancer.getErrorsPerBackend()
                        .getOrDefault(key, new AtomicLong(0)).get());
                
                bStats.put("averageResponseTime", b.getAverageResponseTime());
                bStats.put("totalRequests", b.getTotalRequests());
                
                bStats.put("currentBandwidth", Math.round(b.getCurrentBandwidth()));
                bStats.put("totalBytesTransferred", b.getTotalBytesTransferred());
                bStats.put("bandwidthFormatted", formatBandwidth(b.getCurrentBandwidth()));
                
                backendsStats.put(key, bStats);
            }
            stats.put("backends", backendsStats);

            String json = gson.toJson(stats);

            if (request != null) {
                sendResponse(ctx, request, OK, json.getBytes(CharsetUtil.UTF_8));
            } else {
                ctx.writeAndFlush(new TextWebSocketFrame(json));
            }
        }

        private void handleAddBackend(ChannelHandlerContext ctx, FullHttpRequest request) {
            ByteBuf content = request.content();
            int contentLength = content.readableBytes();
            if (contentLength > 1024 * 1024) {
                sendResponse(ctx, request, BAD_REQUEST, "{\"error\":\"Request body too large\"}".getBytes(CharsetUtil.UTF_8));
                return;
            }
            String body = content.toString(CharsetUtil.UTF_8);
            try {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                String host = json.get("host").getAsString();
                int port = json.get("port").getAsInt();
                
                if (port < 1 || port > 65535) {
                    sendResponse(ctx, request, BAD_REQUEST, "{\"error\":\"Invalid port\"}".getBytes(CharsetUtil.UTF_8));
                    return;
                }

                loadBalancer.addBackend(host, port);
                loadBalancer.forceRefreshAllBackends();
                
                String resp = "{\"status\":\"ok\", \"message\":\"Backend " + host + ":" + port + " added\"}";
                sendResponse(ctx, request, OK, resp.getBytes(CharsetUtil.UTF_8));
            } catch (JsonSyntaxException | IllegalStateException e) {
                log.error("Error adding backend", e);
                String errorMsg = "{\"error\":\"Invalid JSON: " + e.getMessage() + "\"}";
                sendResponse(ctx, request, BAD_REQUEST, errorMsg.getBytes(CharsetUtil.UTF_8));
            }
        }

        private void handleRemoveBackend(ChannelHandlerContext ctx, FullHttpRequest request) {
            ByteBuf content = request.content();
            int contentLength = content.readableBytes();
            if (contentLength > 1024 * 1024) {
                sendResponse(ctx, request, BAD_REQUEST, "{\"error\":\"Request body too large\"}".getBytes(CharsetUtil.UTF_8));
                return;
            }
            String body = content.toString(CharsetUtil.UTF_8);
            try {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                String host = json.get("host").getAsString();
                int port = json.get("port").getAsInt();

                loadBalancer.removeBackend(host, port);
                loadBalancer.forceRefreshAllBackends();
                String resp = "{\"status\":\"ok\", \"message\":\"Backend " + host + ":" + port + " removed\"}";
                sendResponse(ctx, request, OK, resp.getBytes(CharsetUtil.UTF_8));
            } catch (JsonSyntaxException | IllegalStateException e) {
                log.error("Error removing backend", e);
                String errorMsg = "{\"error\":\"Invalid JSON: " + e.getMessage() + "\"}";
                sendResponse(ctx, request, BAD_REQUEST, errorMsg.getBytes(CharsetUtil.UTF_8));
            }
        }

        private void handleRefreshBackends(ChannelHandlerContext ctx, FullHttpRequest request) {
            try {
                loadBalancer.forceRefreshAllBackends();
                String resp = "{\"status\":\"ok\", \"message\":\"All backends status refreshed\"}";
                sendResponse(ctx, request, OK, resp.getBytes(CharsetUtil.UTF_8));
            } catch (Exception e) {
                log.error("Error refreshing backends", e);
                String errorMsg = "{\"error\":\"" + e.getMessage() + "\"}";
                sendResponse(ctx, request, INTERNAL_SERVER_ERROR, errorMsg.getBytes(CharsetUtil.UTF_8));
            }
        }

        private void handleCheckBackend(ChannelHandlerContext ctx, FullHttpRequest request) {
            ByteBuf content = request.content();
            int contentLength = content.readableBytes();
            if (contentLength > 1024 * 1024) {
                sendResponse(ctx, request, BAD_REQUEST, "{\"error\":\"Request body too large\"}".getBytes(CharsetUtil.UTF_8));
                return;
            }
            String body = content.toString(CharsetUtil.UTF_8);
            try {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                String host = json.get("host").getAsString();
                int port = json.get("port").getAsInt();
                
                loadBalancer.checkBackendStatus(host, port);
                
                boolean alive = false;
                for (BackendServer b : loadBalancer.getBackends()) {
                    if (b.getHost().equals(host) && b.getPort() == port) {
                        alive = b.isAlive();
                        break;
                    }
                }
                
                String resp = "{\"status\":\"ok\", \"host\":\"" + host + "\", \"port\":" + port + ", \"alive\":" + alive + "}";
                sendResponse(ctx, request, OK, resp.getBytes(CharsetUtil.UTF_8));
            } catch (JsonSyntaxException | IllegalStateException e) {
                log.error("Error checking backend", e);
                String errorMsg = "{\"error\":\"" + e.getMessage() + "\"}";
                sendResponse(ctx, request, BAD_REQUEST, errorMsg.getBytes(CharsetUtil.UTF_8));
            }
        }

        private void handleBackendsDebug(ChannelHandlerContext ctx, FullHttpRequest request) {
            List<Map<String, Object>> list = loadBalancer.getBackends().stream()
                .map(b -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("host", b.getHost());
                    map.put("port", b.getPort());
                    map.put("alive", b.isAlive());
                    map.put("hashCode", System.identityHashCode(b));
                    return map;
                })
                .collect(Collectors.toList());
            
            Map<String, Object> response = new HashMap<>();
            response.put("backends", list);
            response.put("size", list.size());
            response.put("healthReport", loadBalancer.getHealthStatusReport());
            
            String json = gson.toJson(response);
            sendResponse(ctx, request, OK, json.getBytes(CharsetUtil.UTF_8));
        }

        private void handleHealthDebug(ChannelHandlerContext ctx, FullHttpRequest request) {
            String report = loadBalancer.getHealthStatusReport();
            Map<String, Object> response = new HashMap<>();
            response.put("status", "ok");
            response.put("report", report);
            response.put("backends", loadBalancer.getBackends().stream()
                .map(b -> Map.of(
                    "host", b.getHost(),
                    "port", b.getPort(),
                    "alive", b.isAlive()
                ))
                .collect(Collectors.toList()));
            
            String json = gson.toJson(response);
            sendResponse(ctx, request, OK, json.getBytes(CharsetUtil.UTF_8));
        }

        private void handleSetWeight(ChannelHandlerContext ctx, FullHttpRequest request) {
            ByteBuf content = request.content();
            int contentLength = content.readableBytes();
            if (contentLength > 1024 * 1024) {
                sendResponse(ctx, request, BAD_REQUEST, "{\"error\":\"Request body too large\"}".getBytes(CharsetUtil.UTF_8));
                return;
            }
            String body = content.toString(CharsetUtil.UTF_8);
            try {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                String host = json.get("host").getAsString();
                int port = json.get("port").getAsInt();
                int weight = json.get("weight").getAsInt();

                if (weight < 1) {
                    String errorMsg = "{\"error\":\"Weight must be >= 1\"}";
                    sendResponse(ctx, request, BAD_REQUEST, errorMsg.getBytes(CharsetUtil.UTF_8));
                    return;
                }

                LoadBalancingStrategy strategy = loadBalancer.getStrategy();
                if (strategy instanceof WeightedRoundRobinStrategy weightedStrategy) {
                    weightedStrategy.setWeight(host, port, weight);
                    String resp = "{\"status\":\"ok\", \"message\":\"Weight for " + host + ":" + port + " set to " + weight + "\"}";
                    sendResponse(ctx, request, OK, resp.getBytes(CharsetUtil.UTF_8));
                } else {
                    String errorMsg = "{\"error\":\"Current strategy is not WeightedRoundRobin\"}";
                    sendResponse(ctx, request, BAD_REQUEST, errorMsg.getBytes(CharsetUtil.UTF_8));
                }
            } catch (JsonSyntaxException | IllegalStateException e) {
                log.error("Error setting weight", e);
                String errorMsg = "{\"error\":\"Invalid JSON: " + e.getMessage() + "\"}";
                sendResponse(ctx, request, BAD_REQUEST, errorMsg.getBytes(CharsetUtil.UTF_8));
            }
        }

        private void serveStatic(ChannelHandlerContext ctx, FullHttpRequest request, String uri) {
            String path = uri.isEmpty() || uri.equals("/") ? "/index.html" : uri;
            String resourcePath = "/static" + path;

            try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
                if (in == null) {
                    sendResponse(ctx, request, NOT_FOUND, "404 Not Found".getBytes());
                    return;
                }
                byte[] content = in.readAllBytes();
                String mimeType = getMimeType(path);
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HTTP_1_1, OK, Unpooled.copiedBuffer(content)
                );
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, mimeType);
                response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.length);
                response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");

                ChannelFuture future = ctx.writeAndFlush(response);
                if (!HttpUtil.isKeepAlive(request)) {
                    future.addListener(ChannelFutureListener.CLOSE);
                }
            } catch (Exception e) {
                log.error("Error serving static file: {}", resourcePath, e);
                String errorMsg = "500 Internal Error";
                sendResponse(ctx, request, INTERNAL_SERVER_ERROR, errorMsg.getBytes());
            }
        }

        private String getMimeType(String path) {
            if (path.endsWith(".html")) return "text/html; charset=UTF-8";
            if (path.endsWith(".css")) return "text/css; charset=UTF-8";
            if (path.endsWith(".js")) return "application/javascript; charset=UTF-8";
            if (path.endsWith(".png")) return "image/png";
            if (path.endsWith(".ico")) return "image/x-icon";
            return "application/octet-stream";
        }

        @SuppressWarnings("unused")
        private void sendResponse(ChannelHandlerContext ctx, FullHttpRequest request,
                                  HttpResponseStatus status, byte[] content) {
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HTTP_1_1, status, Unpooled.copiedBuffer(content)
            );

            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, DELETE, OPTIONS");
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.length);

            ChannelFuture future = ctx.writeAndFlush(response);
            future.addListener(ChannelFutureListener.CLOSE);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("API error", cause);
            ctx.close();
        }
    }
}
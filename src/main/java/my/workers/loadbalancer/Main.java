package my.workers.loadbalancer;

import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import my.workers.loadbalancer.api.HttpApiServer;
import my.workers.loadbalancer.health.HealthChecker;
import my.workers.loadbalancer.model.BackendServer;
import my.workers.loadbalancer.strategy.RoundRobinStrategy;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        CopyOnWriteArrayList<BackendServer> backends = new CopyOnWriteArrayList<>(
            Arrays.asList(
                new BackendServer("localhost", 8081),
                new BackendServer("localhost", 8082),
                new BackendServer("localhost", 8083)
            )
        );

        LoadBalancer lb = new LoadBalancer(8080, backends, new RoundRobinStrategy());

        HealthChecker healthChecker = new HealthChecker(backends, 5000);
        healthChecker.start();
        
        lb.setHealthChecker(healthChecker);

        HttpApiServer apiServer = new HttpApiServer(9090, lb);
        apiServer.start();

        lb.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                log.info("Shutdown hook triggered");
                healthChecker.stop();
                apiServer.shutdown();
                lb.shutdownGracefully();
            } catch (Exception e) {
                log.error("Error during shutdown", e);
            }
        }));
    }
}
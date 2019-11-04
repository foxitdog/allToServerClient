package io.github.foxitdog.netty.server;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class NettyClient implements Runnable {
    @Value("${setting.netty.client.host}")
    String host;
    @Value("${setting.netty.client.serverPort}")
    int port;
    @Value("${setting.netty.client.isRestart}")
    boolean isRestart;
    @Value("${setting.netty.client.restartDelay}")
    int restartDelay;

    @Resource
    ApplicationContext applicationContext;

    // 配置服务端的NIO线程组
    EventLoopGroup workerGroup;

    @PostConstruct
    public void init() {
        new Thread(this).start();
    }

    @PreDestroy
    public void destory() {
        // 优雅退出,释放线程池资源
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }

    public void connect() {
        try {
            CustomChannelInitializer cci = applicationContext.getBean(CustomChannelInitializer.class);
            workerGroup = new NioEventLoopGroup();
            Bootstrap b = new Bootstrap();
            b.group(workerGroup);
            b.channel(NioSocketChannel.class);
            b.option(ChannelOption.SO_KEEPALIVE, true);
            b.handler(cci);
            ChannelFuture f = b.connect(host, port).sync();
            f.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            log.error(e.getMessage(), e);
            workerGroup.shutdownGracefully();
        } finally {
            if (!workerGroup.isShutdown() || !workerGroup.isShuttingDown()) {
                workerGroup.shutdownGracefully();
            }
        }
    }

    @Override
    public void run() {
        do {
            try {
                log.info("启动NettyClient host:" + host + " port:" + port);
                connect();
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
            if (isRestart) {
                try {
                    TimeUnit.SECONDS.sleep(restartDelay);
                } catch (InterruptedException e) {
                    log.error(e.getMessage(), e);
                }
            }
        } while (isRestart);
    }
}

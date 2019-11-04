package io.github.foxitdog.netty.server.handler;

import com.alibaba.fastjson.JSONObject;
import io.github.foxitdog.service.MessageHandler;
import io.github.foxitdog.util.Common;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 消息处理器
 */
@Log4j2
public class ServerMessageHandler extends SimpleChannelInboundHandler<JSONObject> {
    @Autowired
    ThreadPoolTaskScheduler threadPoolTaskScheduler;

    @Value("${spring.application.name}")
    String servername;

    @Autowired
    MessageHandler msghandler;

    ScheduledFuture sf;

    ChannelHandlerContext ctx;

    static ConcurrentHashMap<Integer, LinkObject> linkMap = new ConcurrentHashMap<>();

    /**
     * 新的TCP建立回调函数
     */
    @Override
    public void channelActive(ChannelHandlerContext channelHandlerContext) {
        log.info("新的TCP连接建立:" + channelHandlerContext + ",remoteAddress:" + channelHandlerContext.channel().remoteAddress().toString());
        final ChannelHandlerContext c = channelHandlerContext;
        ctx = channelHandlerContext;
        //  固定时间发送心跳信息，防止服务器断开两者间连接
        sf = threadPoolTaskScheduler.scheduleWithFixedDelay(() -> {
            JSONObject heartbeat = Common.getJSONObject("type", "heartbeat");
            write(heartbeat);
            log.info("heartbeat");
        }, 5 * 1000);
        //注册服务
        sendInnerMessage(Common.getJSONObject("type", "init", "server", servername));
    }

    void sendInnerMessage(JSONObject msg) {
        write(Common.getJSONObject("type", "inner", "data", msg));
    }

    public void write(JSONObject json) {
        ctx.writeAndFlush(json);
    }

    @Override
    public void channelInactive(ChannelHandlerContext channelHandlerContext) {
        log.info("TCP连接已断开:" + channelHandlerContext + ",remoteAddress:" + channelHandlerContext.channel().remoteAddress().toString());
        sf.cancel(true);
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, final JSONObject msg) {
        if (log.isDebugEnabled()) {
            log.debug(msg);
        }
        String type = msg.getString("type");
        if (type == null) {
            return;
        }
        if (type.equals("request")) {
            threadPoolTaskScheduler.execute(() -> {
                Object data = msg.get("data");
                data = msghandler.handleMessage(data);
                msg.put("type", "response");
                msg.put("data", data);
                write(msg);
            });
        }
        if (type.equals("response")) {
            // 暂时不需要实现
        }
        if ("inner".equals(type)) {
            JSONObject jo = msg.getJSONObject("data");
            type = jo.getString("type");
            if ("exception".equals(type)) {
                log.error("服务内部错误：code:{},msg:{}", jo.getIntValue("code"), jo.getString("msg"));
                return;
            }
            return;
        }
    }

    /**
     * 异常
     *
     * @param ctx   ChannelHandlerContext对象
     * @param cause Throwable对象
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error(cause.getMessage(), cause);
    }

    static AtomicInteger vint = new AtomicInteger();

    /**
     * 获取非0数
     *
     * @return
     */
    public int getlinksum() {
        int linksum = vint.addAndGet(1);
        if (linksum == 0) {
            return getlinksum();
        }
        return linksum;
    }

    /**
     * 同步发送数据的方法
     *
     * @param message 发送的数据
     * @return 返回的数据
     */
    public JSONObject sendForResult(JSONObject message) {
        int linksum = getlinksum();
        int outTime = message.getJSONObject("meta").getIntValue("outTime");
        if (outTime <= 0) {
            outTime = 60 * 1000;
        }
        message.put("linksum", linksum);
        LinkObject link = new LinkObject();
        synchronized (link) {
            linkMap.put(linksum, link);
            log.debug("sendForResult--message:{}", message);
            ctx.writeAndFlush(message);
            link.state = LinkObject.SendType.SENDED;
            try {
                link.wait(outTime);
            } catch (InterruptedException e) {
                return Common.getNormalFalseJson(e.getMessage());
            }
            linkMap.remove(linksum);
            if (link.state == LinkObject.SendType.SENDED) {
                return Common.getNormalFalseJson("数据返回超时");
            }
            return link.data;
        }
    }

    private static class LinkObject {

        /**
         * 数据
         */
        JSONObject data;

        /**
         * 0:未发 1:已发 2:接受
         */
        SendType state = SendType.NOSEND;

        private enum SendType {
            NOSEND, SENDED, RECEIVED
        }
    }
}
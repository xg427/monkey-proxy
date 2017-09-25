package net.dongliu.byproxy.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;
import net.dongliu.byproxy.MessageListener;
import net.dongliu.byproxy.netty.interceptor.HttpMessageInterceptor;
import net.dongliu.byproxy.utils.NetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Queue;

import static io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS;
import static io.netty.channel.ChannelOption.SO_KEEPALIVE;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_GATEWAY;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Plain http proxy
 */
public class HttpProxyHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(HttpProxyHandler.class);

    private final Bootstrap bootstrap = new Bootstrap();
    private Channel clientOutChannel;
    private NetAddress address;

    private final Queue<HttpContent> queue = new ArrayDeque<>();

    @Nullable
    private MessageListener messageListener;

    public HttpProxyHandler(@Nullable MessageListener messageListener) {
        this.messageListener = messageListener;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof HttpObject)) {
            logger.error("got unknown message type: {}", msg.getClass());
            return;
        }

        if (msg instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) msg;
            logger.debug("http proxy request received: {}", request.uri());
            URL url = new URL(request.uri());
            logger.debug("proxy http url: {}", url);
            String host = url.getHost();
            int port = url.getPort();
            String authority = url.getAuthority();
            request.setUri(url.getFile());
            request.headers().add("Host", host);
            stripRequest(request);

            if (port == -1) {
                port = 80;
            }
            NetAddress address = new NetAddress(host, port);

            if (clientOutChannel != null && clientOutChannel.isActive() && address.equals(this.address)) {
                clientOutChannel.writeAndFlush(request);
                return;
            }

            ctx.channel().config().setAutoRead(false);

            if (clientOutChannel != null) {
                if (clientOutChannel.isActive()) {
                    NettyUtils.closeOnFlush(clientOutChannel);
                }
                clientOutChannel = null;
            }

            logger.debug("begin creating new connection to {}", address);
            Future<Channel> future = newChannel(ctx, address);
            future.addListener((FutureListener<Channel>) f -> {
                logger.debug("new connection to {} established", address);
                Channel channel = f.getNow();
                this.clientOutChannel = channel;
                this.address = address;
                channel.writeAndFlush(request);
                ctx.channel().config().setAutoRead(true);
                flushQueue();
            });
            return;
        }

        if (msg instanceof HttpContent) {
            HttpContent httpContent = (HttpContent) msg;
            queue.add(httpContent);
            flushQueue();
        }
    }


    private void flushQueue() {
        if (clientOutChannel == null) {
            return;
        }
        boolean wrote = false;
        while (true) {
            HttpContent httpContent = queue.poll();
            if (httpContent == null) {
                break;
            }
            clientOutChannel.write(httpContent);
            wrote = true;
        }

        if (wrote) {
            clientOutChannel.flush();
        }
    }

    private Future<Channel> newChannel(ChannelHandlerContext ctx, NetAddress address) {
        Promise<Channel> promise = ctx.executor().newPromise();
        bootstrap.group(ctx.channel().eventLoop())
                .channel(NioSocketChannel.class)
                .option(CONNECT_TIMEOUT_MILLIS, 10000)
                .option(SO_KEEPALIVE, true)
                .handler(new ChannelActiveAwareHandler(promise));

        bootstrap.connect(address.getHost(), address.getPort()).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                ctx.channel().writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, BAD_GATEWAY));
                NettyUtils.closeOnFlush(ctx.channel());
            }
        });

        promise.addListener((FutureListener<Channel>) f -> {
            if (!f.isSuccess()) {
                ctx.channel().writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, BAD_GATEWAY));
                NettyUtils.closeOnFlush(ctx.channel());
                return;
            }
            Channel channel = f.getNow();
            channel.pipeline().addLast(new HttpClientCodec());
            if (messageListener != null) {
                channel.pipeline().addLast(new HttpMessageInterceptor("http", address, messageListener));
            }
            channel.pipeline().addLast(new TunnelProxyHandler(ctx.channel()));
        });
        return promise;
    }

    private void stripRequest(HttpRequest request) {
        request.headers().remove("Proxy-Authenticate");
        request.headers().remove("Proxy-Connection");
        request.headers().remove("Expect");
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (clientOutChannel != null) {
            NettyUtils.closeOnFlush(clientOutChannel);
        }
        releaseQueue();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("", cause);
        NettyUtils.closeOnFlush(ctx.channel());
        releaseQueue();
    }

    private void releaseQueue() {
        while (true) {
            HttpContent httpContent = queue.poll();
            if (httpContent == null) {
                break;
            }
            httpContent.release();
        }
    }
}

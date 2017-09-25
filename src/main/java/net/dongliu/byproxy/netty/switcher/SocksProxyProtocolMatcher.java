package net.dongliu.byproxy.netty.switcher;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.socksx.v4.Socks4ServerDecoder;
import io.netty.handler.codec.socksx.v4.Socks4ServerEncoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import net.dongliu.byproxy.MessageListener;
import net.dongliu.byproxy.netty.tcp.Socks4ProxyAuthHandler;
import net.dongliu.byproxy.netty.tcp.Socks5ProxyAuthHandler;

import javax.annotation.Nullable;

/**
 * Matcher for socks4/socks5 proxy protocol
 */
public class SocksProxyProtocolMatcher implements ProtocolMatcher {

    private int socksVersion;

    @Nullable
    private final MessageListener messageListener;

    public SocksProxyProtocolMatcher(@Nullable MessageListener messageListener) {
        this.messageListener = messageListener;
    }

    @Override
    public int match(ByteBuf buf) {
        if (buf.readableBytes() < 2) {
            return PENDING;
        }
        byte first = buf.getByte(buf.readerIndex());
        byte second = buf.getByte(buf.readerIndex() + 1);
        if (first == 4 || first == 5) {
            socksVersion = first;
            return MATCH;
        }
        return DISMATCH;
    }

    @Override
    public void handlePipeline(ChannelPipeline pipeline) {
        if (socksVersion == 4) {
            pipeline.addLast(Socks4ServerEncoder.INSTANCE);
            pipeline.addLast(new Socks4ServerDecoder());
            pipeline.addLast(new Socks4ProxyAuthHandler(messageListener));
            return;
        }
        if (socksVersion == 5) {
            pipeline.addLast(Socks5ServerEncoder.DEFAULT);
            pipeline.addLast(new Socks5InitialRequestDecoder());
            pipeline.addLast(new Socks5ProxyAuthHandler(messageListener));
            return;
        }
        throw new RuntimeException("should not happen");
    }
}

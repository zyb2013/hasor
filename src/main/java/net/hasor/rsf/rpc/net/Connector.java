/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.rsf.rpc.net;
import io.netty.bootstrap.AbstractBootstrap;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import net.hasor.core.AppContext;
import net.hasor.core.Hasor;
import net.hasor.rsf.InterAddress;
import net.hasor.rsf.RsfEnvironment;
import net.hasor.rsf.domain.*;
import net.hasor.rsf.protocol.rsf.protocol.v1.PoolBlock;
import net.hasor.rsf.protocol.rsf.rsf.RSFProtocolDecoder;
import net.hasor.rsf.protocol.rsf.rsf.RSFProtocolEncoder;
import org.more.future.BasicFuture;
import org.more.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Map;
/**
 * RPC协议连接器，负责创建某个特定RPC协议的网络事件。
 * tips：传入的网络连接，交给{@link LinkPool}进行处理，{@link Connector}本身不维护任何连接。
 * @version : 2017年01月16日
 * @author 赵永春(zyc@hasor.net)
 */
@ChannelHandler.Sharable
public class Connector extends ChannelInboundHandlerAdapter {
    protected Logger logger = LoggerFactory.getLogger(getClass());
    private final String           protocolKey;
    private final String           protocolSechma;
    private final AppContext       appContext;
    private final InterAddress     bindAddress;
    private final InterAddress     gatewayAddress;
    private       RsfChannel       localListener;
    private final LinkPool         linkPool;
    private final ReceivedListener receivedListener;
    private final EventLoopGroup   workLoopGroup;
    public Connector(AppContext appContext, String protocolKey,//
            InterAddress local, InterAddress gateway,//
            ReceivedListener receivedListener, LinkPool linkPool, EventLoopGroup workLoopGroup) {
        //
        this.protocolKey = protocolKey;
        this.appContext = appContext;
        this.bindAddress = local;
        this.gatewayAddress = gateway;
        this.receivedListener = receivedListener;
        this.linkPool = linkPool;
        this.workLoopGroup = workLoopGroup;
        Map<String, String> sechmaMap = appContext.getInstance(RsfEnvironment.class).getSettings().getProtocolSechmaMapping();
        this.protocolSechma = sechmaMap.get(protocolKey);
        if (StringUtils.isBlank(this.protocolSechma))
            throw new NullPointerException(protocolKey + " protocolSechma is unknown.");
    }
    @Override
    public String toString() {
        return "Connector{ protocol='" + protocolKey + "'}";
    }
    //
    //
    /** 接收解析好的 RequestInfo、ResponseInfo 对象，并将它们转发到 {@link ReceivedListener}接口中。 */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        InterAddress dataForm = null;
        if (msg instanceof OptionInfo) {
            String hostPort = converToHostProt(ctx);
            dataForm = this.linkPool.findChannel(hostPort).get().getTarget();
            if (dataForm == null) {
                this.exceptionCaught(ctx, new RsfException(ProtocolStatus.NetworkError, "the " + hostPort + " Connection is not management."));
                return;
            }
        }
        //
        if (msg instanceof RequestInfo) {
            this.receivedListener.receivedMessage(dataForm, (RequestInfo) msg);
            return;
        }
        if (msg instanceof ResponseInfo) {
            this.receivedListener.receivedMessage(dataForm, (ResponseInfo) msg);
            return;
        }
    }
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        InetSocketAddress socketAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        if (socketAddress == null) {
            super.handlerAdded(ctx);
            return;
        }
        String hostAddress = socketAddress.getAddress().getHostAddress();
        int port = socketAddress.getPort();
        String hostPort = hostAddress + ":" + port;
        this.logger.info("connected form {}", hostPort);
        InterAddress target = new InterAddress(this.protocolSechma, hostAddress, port, "unknown");
        this.linkPool.newConnection(hostPort, new RsfChannel(this.protocolKey, target, ctx.channel(), LinkType.In));
    }
    //    @Override
    //    public void channelActive(ChannelHandlerContext ctx) throws Exception {
    //        InetSocketAddress socketAddress = (InetSocketAddress) ctx.channel().remoteAddress();
    //        String hostAddress = socketAddress.getAddress().getHostAddress();
    //        int port = socketAddress.getPort();
    //        String hostPort = hostAddress + ":" + port;
    //        this.logger.info("connected form {}", hostPort);
    //        InterAddress target = new InterAddress(this.protocolSechma, hostAddress, port, "unknown");
    //        this.linkPool.newConnection(hostPort, new RsfChannel(this.protocolKey, target, ctx.channel(), LinkType.In));
    //    }
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        this.exceptionCaught(ctx, null);
    }
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        String hostPort = converToHostProt(ctx);
        if (cause == null) {
            this.logger.error("close socket=" + hostPort + "channel Inactive.");
        } else {
            this.logger.error("close socket=" + hostPort + " with error -> " + cause.getMessage(), cause);
        }
        this.linkPool.closeConnection(hostPort);
        ctx.close();
    }
    //
    //
    /** 监听的本地端口号 */
    public InterAddress getBindAddress() {
        return bindAddress;
    }
    /** 如果工作在内网，这里返回配置的外网映射地址 */
    public InterAddress getGatewayAddress() {
        return gatewayAddress;
    }
    //
    //
    /** 连接到远程机器 */
    public void connectionTo(final InterAddress hostAddress, final BasicFuture<RsfChannel> result) {
        //
        final ChannelDuplexHandler protocolHandler = new CombinedChannelDuplexHandler<ChannelInboundHandler, ChannelOutboundHandler>(//
                Hasor.assertIsNotNull(this.newDecoder()),//
                Hasor.assertIsNotNull(this.newEncoder())//
        );
        //
        Bootstrap boot = new Bootstrap();
        boot.group(this.workLoopGroup);
        boot.channel(NioSocketChannel.class);
        boot.handler(new ChannelInitializer<SocketChannel>() {
            public void initChannel(SocketChannel ch) throws Exception {
                ch.pipeline().addLast(//
                        protocolHandler,// 负责协议解析
                        Connector.this  // 转发RequestInfo、ResponseInfo到RSF
                );
            }
        });
        ChannelFuture future = configBoot(boot).connect(hostAddress.toSocketAddress());
        //
        future.addListener(new ChannelFutureListener() {
            public void operationComplete(ChannelFuture future) throws Exception {
                if (!future.isSuccess()) {
                    future.channel().close();
                    logger.error("connect to {} error.", hostAddress, future.cause());
                    result.failed(future.cause());
                } else {
                    Channel channel = future.channel();
                    logger.info("connect to {} Success.", hostAddress);
                    result.completed(new RsfChannel(protocolKey, bindAddress, channel, LinkType.Out));
                }
            }
        });
    }
    /**
     * 启动本地监听器
     * @param listenLoopGroup 监听器线程组
     */
    public void startListener(NioEventLoopGroup listenLoopGroup) {
        //
        final ChannelDuplexHandler protocolHandler = new CombinedChannelDuplexHandler<ChannelInboundHandler, ChannelOutboundHandler>(//
                Hasor.assertIsNotNull(this.newDecoder()),//
                Hasor.assertIsNotNull(this.newEncoder())//
        );
        //
        ServerBootstrap boot = new ServerBootstrap();
        boot.group(listenLoopGroup, this.workLoopGroup);
        boot.channel(NioServerSocketChannel.class);
        boot.childHandler(new ChannelInitializer<SocketChannel>() {
            public void initChannel(SocketChannel ch) throws Exception {
                ch.pipeline().addLast(//
                        protocolHandler,// 负责协议解析
                        Connector.this  // 转发RequestInfo、ResponseInfo到RSF
                );
            }
        });
        boot.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        boot.childOption(ChannelOption.SO_KEEPALIVE, true);
        ChannelFuture future = configBoot(boot).bind(this.bindAddress.toSocketAddress());
        //
        final BasicFuture<RsfChannel> result = new BasicFuture<RsfChannel>();
        future.addListener(new ChannelFutureListener() {
            public void operationComplete(ChannelFuture future) throws Exception {
                if (!future.isSuccess()) {
                    future.channel().close();
                    result.failed(future.cause());
                } else {
                    Channel channel = future.channel();
                    result.completed(new RsfChannel(protocolKey, bindAddress, channel, LinkType.Listener));
                }
            }
        });
        try {
            this.localListener = result.get();
            logger.info("rsf Server started at {}", this.bindAddress.getHostPort());
        } catch (Exception e) {
            logger.error("rsf start listener error: " + e.getMessage(), e);
            throw new RsfException(ProtocolStatus.NetworkError, e);
        }
        //
    }
    private <T extends AbstractBootstrap<?, ?>> T configBoot(T boot) {
        boot.option(ChannelOption.SO_KEEPALIVE, true);
        // boot.option(ChannelOption.SO_BACKLOG, 128);
        // boot.option(ChannelOption.SO_BACKLOG, 1024);
        // boot.option(ChannelOption.SO_RCVBUF, 1024 * 256);
        // boot.option(ChannelOption.SO_SNDBUF, 1024 * 256);
        boot.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        return boot;
    }
    /**停止监听器*/
    public void shutdown() {
        this.localListener.close();
    }
    //
    //
    private static String converToHostProt(ChannelHandlerContext ctx) {
        InetSocketAddress socketAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        return socketAddress.getAddress().getHostAddress() + ":" + socketAddress.getPort();
    }
    private ChannelInboundHandler newDecoder() {
        RsfEnvironment env = this.appContext.getInstance(RsfEnvironment.class);
        return new RSFProtocolDecoder(env, PoolBlock.DataMaxSize);
    }
    private ChannelOutboundHandler newEncoder() {
        RsfEnvironment env = this.appContext.getInstance(RsfEnvironment.class);
        return new RSFProtocolEncoder(env);
    }
}
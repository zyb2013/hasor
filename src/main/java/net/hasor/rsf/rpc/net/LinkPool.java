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
import io.netty.util.internal.ConcurrentSet;
import org.more.RepeateException;
import org.more.future.BasicFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
/**
 * 维护RSF同其它RSF的连接。
 * tips：主要数据结构为 hostPort 和 RsfChannel 的映射关系。另外还维护了一个 别名关系，通过别名关系实现双向通信上的连接复用问题。
 * @version : 2014年9月12日
 * @author 赵永春(zyc@hasor.net)
 */
public class LinkPool {
    protected Logger logger = LoggerFactory.getLogger(getClass());
    private final ConcurrentMap<String, BasicFuture<RsfChannel>> channelMap;
    private final ConcurrentMap<String, String>                  channelAlias;
    private final Set<String>                                    outLinks;
    //
    public LinkPool() {
        this.channelMap = new ConcurrentHashMap<String, BasicFuture<RsfChannel>>();
        this.channelAlias = new ConcurrentHashMap<String, String>();
        this.outLinks = new ConcurrentSet<String>();
    }
    //
    //
    public synchronized BasicFuture<RsfChannel> preConnection(String hostPortKey) {
        //创建一个Future，并开始计时，在规定时间内没有连接成功则反馈失败（目的防止其它线程在Future的get上被锁死）
        BasicFuture<RsfChannel> channel = new BasicFuture<RsfChannel>();
        BasicFuture<RsfChannel> future = this.channelMap.putIfAbsent(hostPortKey, channel);
        if (future != null) {
            return future;
        } else {
            return channel;
        }
    }
    public void newConnection(String hostPortKey, RsfChannel channel) {
        BasicFuture<RsfChannel> future = this.preConnection(hostPortKey);
        if (future.isDone()) {
            throw new RepeateException("socket -> " + hostPortKey);
        }
        future.completed(channel);
    }
    public void closeConnection(String hostPortKey) {
        BasicFuture<RsfChannel> future = this.preConnection(hostPortKey);
        this.channelMap.remove(hostPortKey);
        if (future.isDone()) {
            try {
                future.get().close();
            } catch (Exception e) { /**/ }
        }
    }
    /**
     * 查找连接
     * @param hostPortKey  liek this 127.0.0.1:2180
     */
    public BasicFuture<RsfChannel> findChannel(String hostPortKey) {
        return this.channelMap.get(hostPortKey);
    }
}
/*
 * Copyright (c) 2016 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.couchbase.client.core.endpoint;

import com.couchbase.client.core.ResponseEvent;
import com.couchbase.client.core.ResponseHandler;
import com.couchbase.client.core.endpoint.kv.AuthenticationException;
import com.couchbase.client.core.env.CoreEnvironment;
import com.couchbase.client.core.logging.CouchbaseLogger;
import com.couchbase.client.core.logging.CouchbaseLoggerFactory;
import com.couchbase.client.core.message.CouchbaseRequest;
import com.couchbase.client.core.message.internal.SignalConfigReload;
import com.couchbase.client.core.message.internal.SignalFlush;
import com.couchbase.client.core.state.AbstractStateMachine;
import com.couchbase.client.core.state.LifecycleState;
import com.couchbase.client.core.state.NotConnectedException;
import com.lmax.disruptor.RingBuffer;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.socket.oio.OioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslHandler;
import rx.Observable;
import rx.Single;
import rx.SingleSubscriber;
import rx.Subscriber;
import rx.functions.Func1;
import rx.subjects.AsyncSubject;
import rx.subjects.Subject;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.couchbase.client.core.utils.Observables.failSafe;

/**
 * The common parent implementation for all {@link Endpoint}s.
 *
 * This parent implementation provides common functionality that all {@link Endpoint}s need, most notably
 * bootstrapping, connecting and reconnecting.
 *
 * @author Michael Nitschinger
 * @since 1.0
 */
public abstract class AbstractEndpoint extends AbstractStateMachine<LifecycleState> implements Endpoint {

    /**
     * The logger used.
     */
    private static final CouchbaseLogger LOGGER = CouchbaseLoggerFactory.getInstance(Endpoint.class);

    /**
     * A shared logging handler for all endpoints.
     */
    private static final ChannelHandler LOGGING_HANDLER_INSTANCE = new LoggingHandler(LogLevel.TRACE);

    /**
     * Pre-created not connected exception for performance reasons.
     */
    private static final NotConnectedException NOT_CONNECTED_EXCEPTION = new NotConnectedException();

    /**
     * If the callback does not return, what additional delay should be set over the socket connect
     * timeout so that it returns eventually and is not stuck for a long time (in ms).
     */
    private static final String DEFAULT_CONNECT_CALLBACK_GRACE_PERIOD = "2000";

    /**
     * The netty bootstrap adapter.
     */
    private final BootstrapAdapter bootstrap;

    /**
     * The name of the couchbase bucket (needed for bucket-level endpoints).
     */
    private final String bucket;

    /**
     * The password of the couchbase bucket (needed for bucket-level endpoints).
     */
    private final String password;

    /**
     * The reference to the response buffer to publish response events.
     */
    private final RingBuffer<ResponseEvent> responseBuffer;

    /**
     * Reference to the overall {@link CoreEnvironment}.
     */
    private final CoreEnvironment env;

    /**
     * Defines if the endpoint should destroy itself after one successful msg.
     */
    private final boolean isTransient;


    private final int connectCallbackGracePeriod;

    private final EventLoopGroup ioPool;

    /**
     * Factory which handles {@link SSLEngine} creation.
     */
    private SSLEngineFactory sslEngineFactory;

    /**
     * The underlying IO (netty) channel.
     */
    private volatile Channel channel;

    /**
     * True if there have been operations written, pending flush.
     */
    private volatile boolean hasWritten;

    /**
     * Number of reconnects already done.
     */
    private volatile long reconnectAttempt = 1;

    /**
     * Set to true once disconnected.
     */
    private volatile boolean disconnected;

    /**
     * Preset the stack trace for the static exceptions.
     */
    static {
        NOT_CONNECTED_EXCEPTION.setStackTrace(new StackTraceElement[0]);
    }

    /**
     * Constructor to which allows to pass in an artificial bootstrap adapter.
     *
     * This method should not be used outside of tests. Please use the
     * {@link #AbstractEndpoint(String, String, String, int, CoreEnvironment, RingBuffer, boolean, EventLoopGroup)}
     * constructor instead.
     *
     * @param bucket the name of the bucket.
     * @param password the password of the bucket.
     * @param adapter the bootstrap adapter.
     */
    protected AbstractEndpoint(final String bucket, final String password, final BootstrapAdapter adapter,
        final boolean isTransient, CoreEnvironment env) {
        super(LifecycleState.DISCONNECTED);
        bootstrap = adapter;
        this.bucket = bucket;
        this.password = password;
        this.responseBuffer = null;
        this.env = env;
        this.isTransient = isTransient;
        this.disconnected = false;
        this.connectCallbackGracePeriod = Integer.parseInt(DEFAULT_CONNECT_CALLBACK_GRACE_PERIOD);
        this.ioPool = env.ioPool();
    }

    /**
     * Create a new {@link AbstractEndpoint}.
     *
     * @param hostname the hostname/ipaddr of the remote channel.
     * @param bucket the name of the bucket.
     * @param password the password of the bucket.
     * @param port the port of the remote channel.
     * @param environment the environment of the core.
     * @param responseBuffer the response buffer for passing responses up the stack.
     */
    protected AbstractEndpoint(final String hostname, final String bucket, final String password, final int port,
        final CoreEnvironment environment, final RingBuffer<ResponseEvent> responseBuffer, boolean isTransient,
        final EventLoopGroup ioPool) {
        super(LifecycleState.DISCONNECTED);
        this.bucket = bucket;
        this.password = password;
        this.responseBuffer = responseBuffer;
        this.env = environment;
        this.isTransient = isTransient;
        this.ioPool = ioPool;
        this.connectCallbackGracePeriod = Integer.parseInt(
            System.getProperty("com.couchbase.connectCallbackGracePeriod", DEFAULT_CONNECT_CALLBACK_GRACE_PERIOD)
        );
        LOGGER.debug("Using a connectCallbackGracePeriod of {} on Endpoint {}:{}", connectCallbackGracePeriod,
            hostname, port);
        if (environment.sslEnabled()) {
            this.sslEngineFactory = new SSLEngineFactory(environment);
        }

        Class<? extends Channel> channelClass = NioSocketChannel.class;
        if (ioPool instanceof EpollEventLoopGroup) {
            channelClass = EpollSocketChannel.class;
        } else if (ioPool instanceof OioEventLoopGroup) {
            channelClass = OioSocketChannel.class;
        }

        ByteBufAllocator allocator = env.bufferPoolingEnabled()
                ? PooledByteBufAllocator.DEFAULT : UnpooledByteBufAllocator.DEFAULT;

        boolean tcpNodelay = environment().tcpNodelayEnabled();
        bootstrap = new BootstrapAdapter(new Bootstrap()
            .remoteAddress(hostname, port)
            .group(ioPool)
            .channel(channelClass)
            .option(ChannelOption.ALLOCATOR, allocator)
            .option(ChannelOption.TCP_NODELAY, tcpNodelay)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, env.socketConnectTimeout())
            .handler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel channel) throws Exception {
                    ChannelPipeline pipeline = channel.pipeline();
                    if (environment.sslEnabled()) {
                        pipeline.addLast(new SslHandler(sslEngineFactory.get()));
                    }
                    if (LOGGER.isTraceEnabled()) {
                        pipeline.addLast(LOGGING_HANDLER_INSTANCE);
                    }
                    customEndpointHandlers(pipeline);
                }
            }));
    }

    /**
     * Add custom endpoint handlers to the {@link ChannelPipeline}.
     *
     * This method needs to be implemented by the actual endpoint implementations to add specific handlers to
     * the pipeline depending on the endpoint type and intended behavior.
     *
     * @param pipeline the pipeline where to add handlers.
     */
    protected abstract void customEndpointHandlers(ChannelPipeline pipeline);

    @Override
    public Observable<LifecycleState> connect() {
        return connect(true);
    }

    /**
     * An internal alternative to {@link #connect()} where signalling that this is
     * post-bootstrapping can be done.
     *
     * @param bootstrapping is this connect attempt made during bootstrap or after (in
     *                     which case more error cases are eligible for retries).
     */
    protected Observable<LifecycleState> connect(boolean bootstrapping) {
        if (state() != LifecycleState.DISCONNECTED) {
            return Observable.just(state());
        }

        final AsyncSubject<LifecycleState> observable = AsyncSubject.create();
        transitionState(LifecycleState.CONNECTING);
        hasWritten = false;
        doConnect(observable, bootstrapping);
        return observable;
    }

    /**
     * Helper method to perform the actual connect and reconnect.
     *
     * @param observable the {@link Subject} which is eventually notified if the connect process
     *                   succeeded or failed.
     * @param bootstrapping true if connection attempt is for bootstrapping phase and therefore be less forgiving of
     *                      some errors (like socket connect timeout).
     */
    protected void doConnect(final Subject<LifecycleState, LifecycleState> observable, final boolean bootstrapping) {
        Single.create(new Single.OnSubscribe<ChannelFuture>() {
            @Override
            public void call(final SingleSubscriber<? super ChannelFuture> ss) {
                bootstrap.connect().addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture cf) throws Exception {
                        ss.onSuccess(cf);
                    }
                });
            }
        })
        // Safeguard if the callback doesn't return after the socket timeout + a grace period of one second.
        .timeout(env.socketConnectTimeout() + connectCallbackGracePeriod, TimeUnit.MILLISECONDS)
        .onErrorResumeNext(new Func1<Throwable, Single<? extends ChannelFuture>>() {
            @Override
            public Single<? extends ChannelFuture> call(Throwable throwable) {
                ChannelPromise promise = new DefaultChannelPromise(null, ioPool.next());
                if (throwable instanceof TimeoutException) {
                    // Explicitly convert our timeout safeguard into a ConnectTimeoutException to simulate
                    // a socket connect timeout.
                    promise.setFailure(new ConnectTimeoutException("Connect callback did not return, "
                        + "hit safeguarding timeout."));
                } else {
                    promise.setFailure(throwable);
                }
                return Single.just(promise);
            }
        })
        .subscribe(new SingleSubscriber<ChannelFuture>() {
            @Override
            public void onSuccess(ChannelFuture future) {
                if (state() == LifecycleState.DISCONNECTING || state() == LifecycleState.DISCONNECTED) {
                    LOGGER.debug(logIdent(channel, AbstractEndpoint.this) + "Endpoint connect completed, "
                            + "but got instructed to disconnect in the meantime.");
                    transitionState(LifecycleState.DISCONNECTED);
                    channel = null;
                } else {
                    if (future.isSuccess()) {
                        channel = future.channel();
                        LOGGER.debug(logIdent(channel, AbstractEndpoint.this) + "Connected Endpoint.");
                        transitionState(LifecycleState.CONNECTED);
                    } else {
                        if (future.cause() instanceof AuthenticationException) {
                            LOGGER.warn(logIdent(channel, AbstractEndpoint.this)
                                    + "Authentication Failure.");
                            transitionState(LifecycleState.DISCONNECTED);
                            observable.onError(future.cause());
                        } else if (future.cause() instanceof SSLHandshakeException) {
                            LOGGER.warn(logIdent(channel, AbstractEndpoint.this)
                                    + "SSL Handshake Failure during connect.");
                            transitionState(LifecycleState.DISCONNECTED);
                            observable.onError(future.cause());
                        } else if (future.cause() instanceof ClosedChannelException) {
                            LOGGER.warn(logIdent(channel, AbstractEndpoint.this)
                                    + "Generic Failure.");
                            transitionState(LifecycleState.DISCONNECTED);
                            LOGGER.warn(future.cause().getMessage());
                            observable.onError(future.cause());
                        } else if (future.cause() instanceof ConnectTimeoutException) {
                            LOGGER.warn(logIdent(channel, AbstractEndpoint.this)
                                    + "Socket connect took longer than specified timeout.");
                            transitionState(LifecycleState.DISCONNECTED);
                            observable.onError(future.cause());
                        } else if (future.cause() instanceof ConnectException) {
                            LOGGER.warn(logIdent(channel, AbstractEndpoint.this)
                                    + "Could not connect to remote socket.");
                            transitionState(LifecycleState.DISCONNECTED);
                            observable.onError(future.cause());
                        } else if (isTransient) {
                            transitionState(LifecycleState.DISCONNECTED);
                            LOGGER.warn(future.cause().getMessage());
                            observable.onError(future.cause());
                        } else {
                            LOGGER.debug("Unhandled exception during channel connect, ignoring.", future.cause());
                        }

                        if (bootstrapping) {
                            // we need to reconnect sockets, but the original observable has been failed already.
                            // so transparently initiate the reconnect loop so if bootstrap succeeds and this endpoint
                            // comes back online later it is picked up properly.
                            connect(false).subscribe(new Subscriber<LifecycleState>() {
                                @Override
                                public void onCompleted() {}

                                @Override
                                public void onNext(LifecycleState lifecycleState) {}

                                @Override
                                public void onError(Throwable e) {
                                    LOGGER.warn("Error during reconnect: ", e);
                                }
                            });
                        } else if (!disconnected && !isTransient) {
                            long delay = env.reconnectDelay().calculate(reconnectAttempt++);
                            TimeUnit delayUnit = env.reconnectDelay().unit();
                            LOGGER.warn(logIdent(channel, AbstractEndpoint.this)
                                    + "Could not connect to endpoint, retrying with delay " + delay + " "
                                    + delayUnit + ": ", future.cause());
                            if (responseBuffer != null) {
                                responseBuffer.publishEvent(ResponseHandler.RESPONSE_TRANSLATOR,
                                        SignalConfigReload.INSTANCE, null);
                            }
                            transitionState(LifecycleState.CONNECTING);
                            EventLoop ev = future.channel() != null ? future.channel().eventLoop() : ioPool.next();
                            ev.schedule(new Runnable() {
                                @Override
                                public void run() {
                                    // Make sure to avoid a race condition where the reconnect could override
                                    // the disconnect phase. If this happens, explicitly break the retry loop
                                    // and re-run the disconnect phase to make sure all is properly freed.
                                    if (!disconnected) {
                                        doConnect(observable, bootstrapping);
                                    } else {
                                        LOGGER.debug("{}Explicitly breaking retry loop because already disconnected.",
                                                logIdent(channel, AbstractEndpoint.this));
                                        disconnect();
                                    }
                                }
                            }, delay, delayUnit);
                        } else {
                            LOGGER.debug("{}Not retrying because already disconnected or transient.",
                                    logIdent(channel, AbstractEndpoint.this));
                        }
                    }
                }
                observable.onNext(state());
                observable.onCompleted();
            }

            @Override
            public void onError(Throwable error) {
                // All errors are converted to failed ChannelFutures before, so this observable
                // should never fail.
                LOGGER.warn("Unexpected error on connect callback wrapper, this is a bug.", error);
            }
        });
    }


    @Override
    public Observable<LifecycleState> disconnect() {
        disconnected = true;

        if (state() == LifecycleState.DISCONNECTED || state() == LifecycleState.DISCONNECTING) {
            return Observable.just(state());
        }

        if (state() == LifecycleState.CONNECTING) {
            transitionState(LifecycleState.DISCONNECTED);
            return Observable.just(state());
        }

        transitionState(LifecycleState.DISCONNECTING);
        final AsyncSubject<LifecycleState> observable = AsyncSubject.create();
        channel.disconnect().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    LOGGER.debug(logIdent(channel, AbstractEndpoint.this) + "Disconnected Endpoint.");
                } else {
                    LOGGER.warn(logIdent(channel, AbstractEndpoint.this) + "Received an error "
                        + "during disconnect.", future.cause());
                }
                transitionState(LifecycleState.DISCONNECTED);
                observable.onNext(state());
                observable.onCompleted();
                channel = null;
            }
        });
        return observable;
    }

    @Override
    public void send(final CouchbaseRequest request) {
        if (state() == LifecycleState.CONNECTED) {
            if (request instanceof SignalFlush) {
                if (hasWritten && channel.isActive()) {
                    channel.flush();
                    hasWritten = false;
                }
            } else {
                if (channel.isActive() && channel.isWritable()) {
                    channel.write(request, channel.voidPromise());
                    hasWritten = true;
                } else {
                    responseBuffer.publishEvent(ResponseHandler.RESPONSE_TRANSLATOR, request, request.observable());
                }
            }
        } else {
            if (request instanceof SignalFlush) {
                return;
            }
            failSafe(env.scheduler(), true, request.observable(), NOT_CONNECTED_EXCEPTION);
        }
    }

    /**
     * Helper method that is called from inside the event loop to notify the upper {@link Endpoint} of a disconnect.
     *
     * Note that the connect method is only called if the endpoint is currently connected, since otherwise this would
     * try to connect to a socket which has already been removed on a failover/rebalance out.
     *
     * Subsequent reconnect attempts are triggered from here.
     *
     * A config reload is only signalled if the current endpoint is not in a DISCONNECTED state, avoiding to signal
     * a config reload under the case of a regular, intended channel close (in an unexpected socket close, the
     * endpoint is in a connected or connecting state).
     */
    public void notifyChannelInactive() {
        if (isTransient) {
            return;
        }
        LOGGER.info(logIdent(channel, this) + "Got notified from Channel as inactive, " +
                "attempting reconnect.");

        if (state() != LifecycleState.DISCONNECTED && state() != LifecycleState.DISCONNECTING) {
            signalConfigReload();
        }

        if (state() == LifecycleState.CONNECTED || state() == LifecycleState.CONNECTING) {
            transitionState(LifecycleState.DISCONNECTED);
            connect(false).subscribe(new Subscriber<LifecycleState>() {
                @Override
                public void onCompleted() {}

                @Override
                public void onNext(LifecycleState lifecycleState) {}

                @Override
                public void onError(Throwable e) {
                    LOGGER.warn("Error during reconnect: ", e);
                }
            });
        }
    }

    /**
     * Signal a "config reload" event to the upper config layers.
     */
    public void signalConfigReload() {
        responseBuffer.publishEvent(ResponseHandler.RESPONSE_TRANSLATOR, SignalConfigReload.INSTANCE, null);
    }

    /**
     * The name of the bucket.
     *
     * @return the bucket name.
     */
    protected String bucket() {
        return bucket;
    }

    /**
     * The password of the bucket.
     *
     * @return the bucket password.
     */
    protected String password() {
        return password;
    }

    /**
     * The {@link CoreEnvironment} reference.
     *
     * @return the environment.
     */
    public CoreEnvironment environment() {
        return env;
    }

    /**
     * The {@link RingBuffer} response buffer reference.
     *
     * @return the response buffer.
     */
    public RingBuffer<ResponseEvent> responseBuffer() {
        return responseBuffer;
    }

    /**
     * Simple log helper to give logs a common prefix.
     *
     * @param chan the address.
     * @param endpoint the endpoint.
     * @return a prefix string for logs.
     */
    protected static String logIdent(final Channel chan, final Endpoint endpoint) {
        SocketAddress addr = chan != null ? chan.remoteAddress() : null;
        return "[" + addr + "][" + endpoint.getClass().getSimpleName() + "]: ";
    }

}

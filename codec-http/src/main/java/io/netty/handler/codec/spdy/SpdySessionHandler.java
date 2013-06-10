/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.spdy;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.MessageList;
import io.netty.util.internal.EmptyArrays;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages streams within a SPDY session.
 */
public class SpdySessionHandler
        extends ChannelDuplexHandler {

    private static final SpdyProtocolException PROTOCOL_EXCEPTION = new SpdyProtocolException();
    private static final SpdyProtocolException STREAM_CLOSED = new SpdyProtocolException("Stream closed");

    static {
        PROTOCOL_EXCEPTION.setStackTrace(EmptyArrays.EMPTY_STACK_TRACE);
        STREAM_CLOSED.setStackTrace(EmptyArrays.EMPTY_STACK_TRACE);
    }

    private final SpdySession spdySession = new SpdySession();
    private volatile int lastGoodStreamId;

    private volatile int remoteConcurrentStreams;
    private volatile int localConcurrentStreams;
    private volatile int maxConcurrentStreams;

    private static final int DEFAULT_WINDOW_SIZE = 64 * 1024; // 64 KB default initial window size
    private volatile int initialSendWindowSize = DEFAULT_WINDOW_SIZE;
    private volatile int initialReceiveWindowSize = DEFAULT_WINDOW_SIZE;

    private final Object flowControlLock = new Object();

    private final AtomicInteger pings = new AtomicInteger();

    private volatile boolean sentGoAwayFrame;
    private volatile boolean receivedGoAwayFrame;

    private volatile ChannelPromise closeSessionFuture;

    private final boolean server;
    private final boolean flowControl;

    /**
     * Creates a new session handler.
     *
     * @param version the protocol version
     * @param server  {@code true} if and only if this session handler should
     *                handle the server endpoint of the connection.
     *                {@code false} if and only if this session handler should
     *                handle the client endpoint of the connection.
     */
    public SpdySessionHandler(int version, boolean server) {
        if (version < SpdyConstants.SPDY_MIN_VERSION || version > SpdyConstants.SPDY_MAX_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported version: " + version);
        }
        this.server = server;
        flowControl = version >= 3;
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageList<Object> in) throws Exception {
        boolean handled = false;
        MessageList<Object> out = MessageList.newInstance();
        for (int i = 0 ; i < in.size(); i++) {
            Object msg = in.get(i);
            if (msg == null) {
                break;
            }

            if (msg instanceof SpdySynStreamFrame) {
                // Let the next handlers handle the buffered messages before SYN_STREAM message updates the
                // lastGoodStreamId.
                if (handled) {
                    ctx.fireMessageReceived(out);
                    out = MessageList.newInstance();
                }
            }

            handleInboundMessage(ctx, msg, out);
            handled = true;
        }

        in.recycle();
        ctx.fireMessageReceived(out);
    }

    private void handleInboundMessage(ChannelHandlerContext ctx, Object msg, MessageList<Object> out) throws Exception {

        if (msg instanceof SpdyDataFrame) {

            /*
             * SPDY Data frame processing requirements:
             *
             * If an endpoint receives a data frame for a Stream-ID which is not open
             * and the endpoint has not sent a GOAWAY frame, it must issue a stream error
             * with the error code INVALID_STREAM for the Stream-ID.
             *
             * If an endpoint which created the stream receives a data frame before receiving
             * a SYN_REPLY on that stream, it is a protocol error, and the recipient must
             * issue a stream error with the getStatus code PROTOCOL_ERROR for the Stream-ID.
             *
             * If an endpoint receives multiple data frames for invalid Stream-IDs,
             * it may close the session.
             *
             * If an endpoint refuses a stream it must ignore any data frames for that stream.
             *
             * If an endpoint receives a data frame after the stream is half-closed from the
             * sender, it must send a RST_STREAM frame with the getStatus STREAM_ALREADY_CLOSED.
             *
             * If an endpoint receives a data frame after the stream is closed, it must send
             * a RST_STREAM frame with the getStatus PROTOCOL_ERROR.
             */

            SpdyDataFrame spdyDataFrame = (SpdyDataFrame) msg;
            int streamID = spdyDataFrame.getStreamId();

            // Check if we received a data frame for a Stream-ID which is not open
            if (!spdySession.isActiveStream(streamID)) {
                if (streamID <= lastGoodStreamId) {
                    issueStreamError(ctx, streamID, SpdyStreamStatus.PROTOCOL_ERROR, out);
                } else if (!sentGoAwayFrame) {
                    issueStreamError(ctx, streamID, SpdyStreamStatus.INVALID_STREAM, out);
                }
                return;
            }

            // Check if we received a data frame for a stream which is half-closed
            if (spdySession.isRemoteSideClosed(streamID)) {
                issueStreamError(ctx, streamID, SpdyStreamStatus.STREAM_ALREADY_CLOSED, out);
                return;
            }

            // Check if we received a data frame before receiving a SYN_REPLY
            if (!isRemoteInitiatedID(streamID) && !spdySession.hasReceivedReply(streamID)) {
                issueStreamError(ctx, streamID, SpdyStreamStatus.PROTOCOL_ERROR, out);
                return;
            }

            /*
            * SPDY Data frame flow control processing requirements:
            *
            * Recipient should not send a WINDOW_UPDATE frame as it consumes the last data frame.
            */

            if (flowControl) {
                // Update receive window size
                int deltaWindowSize = -1 * spdyDataFrame.content().readableBytes();
                int newWindowSize = spdySession.updateReceiveWindowSize(streamID, deltaWindowSize);

                // Window size can become negative if we sent a SETTINGS frame that reduces the
                // size of the transfer window after the peer has written data frames.
                // The value is bounded by the length that SETTINGS frame decrease the window.
                // This difference is stored for the session when writing the SETTINGS frame
                // and is cleared once we send a WINDOW_UPDATE frame.
                if (newWindowSize < spdySession.getReceiveWindowSizeLowerBound(streamID)) {
                    issueStreamError(ctx, streamID, SpdyStreamStatus.FLOW_CONTROL_ERROR, out);
                    return;
                }

                // Window size became negative due to sender writing frame before receiving SETTINGS
                // Send data frames upstream in initialReceiveWindowSize chunks
                if (newWindowSize < 0) {
                    while (spdyDataFrame.content().readableBytes() > initialReceiveWindowSize) {
                        SpdyDataFrame partialDataFrame = new DefaultSpdyDataFrame(streamID,
                                spdyDataFrame.content().readSlice(initialReceiveWindowSize).retain());
                        ctx.write(partialDataFrame);
                    }
                }

                // Send a WINDOW_UPDATE frame if less than half the window size remains
                if (newWindowSize <= initialReceiveWindowSize / 2 && !spdyDataFrame.isLast()) {
                    deltaWindowSize = initialReceiveWindowSize - newWindowSize;
                    spdySession.updateReceiveWindowSize(streamID, deltaWindowSize);
                    SpdyWindowUpdateFrame spdyWindowUpdateFrame =
                            new DefaultSpdyWindowUpdateFrame(streamID, deltaWindowSize);
                    ctx.write(spdyWindowUpdateFrame);
                }
            }

            // Close the remote side of the stream if this is the last frame
            if (spdyDataFrame.isLast()) {
                halfCloseStream(streamID, true);
            }

        } else if (msg instanceof SpdySynStreamFrame) {

            /*
             * SPDY SYN_STREAM frame processing requirements:
             *
             * If an endpoint receives a SYN_STREAM with a Stream-ID that is less than
             * any previously received SYN_STREAM, it must issue a session error with
             * the getStatus PROTOCOL_ERROR.
             *
             * If an endpoint receives multiple SYN_STREAM frames with the same active
             * Stream-ID, it must issue a stream error with the getStatus code PROTOCOL_ERROR.
             *
             * The recipient can reject a stream by sending a stream error with the
             * getStatus code REFUSED_STREAM.
             */

            SpdySynStreamFrame spdySynStreamFrame = (SpdySynStreamFrame) msg;
            int streamID = spdySynStreamFrame.getStreamId();

            // Check if we received a valid SYN_STREAM frame
            if (spdySynStreamFrame.isInvalid() ||
                !isRemoteInitiatedID(streamID) ||
                spdySession.isActiveStream(streamID)) {
                issueStreamError(ctx, streamID, SpdyStreamStatus.PROTOCOL_ERROR, out);
                return;
            }

            // Stream-IDs must be monotonically increasing
            if (streamID <= lastGoodStreamId) {
                issueSessionError(ctx, SpdySessionStatus.PROTOCOL_ERROR);
                return;
            }

            // Try to accept the stream
            byte priority = spdySynStreamFrame.getPriority();
            boolean remoteSideClosed = spdySynStreamFrame.isLast();
            boolean localSideClosed = spdySynStreamFrame.isUnidirectional();
            if (!acceptStream(streamID, priority, remoteSideClosed, localSideClosed)) {
                issueStreamError(ctx, streamID, SpdyStreamStatus.REFUSED_STREAM, out);
                return;
            }

        } else if (msg instanceof SpdySynReplyFrame) {

            /*
             * SPDY SYN_REPLY frame processing requirements:
             *
             * If an endpoint receives multiple SYN_REPLY frames for the same active Stream-ID
             * it must issue a stream error with the getStatus code STREAM_IN_USE.
             */

            SpdySynReplyFrame spdySynReplyFrame = (SpdySynReplyFrame) msg;
            int streamID = spdySynReplyFrame.getStreamId();

            // Check if we received a valid SYN_REPLY frame
            if (spdySynReplyFrame.isInvalid() ||
                isRemoteInitiatedID(streamID) ||
                spdySession.isRemoteSideClosed(streamID)) {
                issueStreamError(ctx, streamID, SpdyStreamStatus.INVALID_STREAM, out);
                return;
            }

            // Check if we have received multiple frames for the same Stream-ID
            if (spdySession.hasReceivedReply(streamID)) {
                issueStreamError(ctx, streamID, SpdyStreamStatus.STREAM_IN_USE, out);
                return;
            }

            spdySession.receivedReply(streamID);

            // Close the remote side of the stream if this is the last frame
            if (spdySynReplyFrame.isLast()) {
                halfCloseStream(streamID, true);
            }

        } else if (msg instanceof SpdyRstStreamFrame) {

            /*
             * SPDY RST_STREAM frame processing requirements:
             *
             * After receiving a RST_STREAM on a stream, the receiver must not send
             * additional frames on that stream.
             *
             * An endpoint must not send a RST_STREAM in response to a RST_STREAM.
             */

            SpdyRstStreamFrame spdyRstStreamFrame = (SpdyRstStreamFrame) msg;
            removeStream(ctx, spdyRstStreamFrame.getStreamId());

        } else if (msg instanceof SpdySettingsFrame) {

            SpdySettingsFrame spdySettingsFrame = (SpdySettingsFrame) msg;

            int newConcurrentStreams =
                spdySettingsFrame.getValue(SpdySettingsFrame.SETTINGS_MAX_CONCURRENT_STREAMS);
            if (newConcurrentStreams >= 0) {
                updateConcurrentStreams(newConcurrentStreams, true);
            }

            // Persistence flag are inconsistent with the use of SETTINGS to communicate
            // the initial window size. Remove flags from the sender requesting that the
            // value be persisted. Remove values that the sender indicates are persisted.
            if (spdySettingsFrame.isPersisted(SpdySettingsFrame.SETTINGS_INITIAL_WINDOW_SIZE)) {
                spdySettingsFrame.removeValue(SpdySettingsFrame.SETTINGS_INITIAL_WINDOW_SIZE);
            }
            spdySettingsFrame.setPersistValue(SpdySettingsFrame.SETTINGS_INITIAL_WINDOW_SIZE, false);

            if (flowControl) {
                int newInitialWindowSize =
                    spdySettingsFrame.getValue(SpdySettingsFrame.SETTINGS_INITIAL_WINDOW_SIZE);
                if (newInitialWindowSize >= 0) {
                    updateInitialSendWindowSize(newInitialWindowSize);
                }
            }

        } else if (msg instanceof SpdyPingFrame) {

            /*
             * SPDY PING frame processing requirements:
             *
             * Receivers of a PING frame should send an identical frame to the sender
             * as soon as possible.
             *
             * Receivers of a PING frame must ignore frames that it did not initiate
             */

            SpdyPingFrame spdyPingFrame = (SpdyPingFrame) msg;

            if (isRemoteInitiatedID(spdyPingFrame.getId())) {
                ctx.write(spdyPingFrame);
                return;
            }

            // Note: only checks that there are outstanding pings since uniqueness is not enforced
            if (pings.get() == 0) {
                return;
            }
            pings.getAndDecrement();

        } else if (msg instanceof SpdyGoAwayFrame) {

            receivedGoAwayFrame = true;

        } else if (msg instanceof SpdyHeadersFrame) {

            SpdyHeadersFrame spdyHeadersFrame = (SpdyHeadersFrame) msg;
            int streamID = spdyHeadersFrame.getStreamId();

            // Check if we received a valid HEADERS frame
            if (spdyHeadersFrame.isInvalid()) {
                issueStreamError(ctx, streamID, SpdyStreamStatus.PROTOCOL_ERROR, out);
                return;
            }

            if (spdySession.isRemoteSideClosed(streamID)) {
                issueStreamError(ctx, streamID, SpdyStreamStatus.INVALID_STREAM, out);
                return;
            }

            // Close the remote side of the stream if this is the last frame
            if (spdyHeadersFrame.isLast()) {
                halfCloseStream(streamID, true);
            }

        } else if (msg instanceof SpdyWindowUpdateFrame) {

            /*
             * SPDY WINDOW_UPDATE frame processing requirements:
             *
             * Receivers of a WINDOW_UPDATE that cause the window size to exceed 2^31
             * must send a RST_STREAM with the getStatus code FLOW_CONTROL_ERROR.
             *
             * Sender should ignore all WINDOW_UPDATE frames associated with a stream
             * after sending the last frame for the stream.
             */

            if (flowControl) {
                SpdyWindowUpdateFrame spdyWindowUpdateFrame = (SpdyWindowUpdateFrame) msg;
                int streamID = spdyWindowUpdateFrame.getStreamId();
                int deltaWindowSize = spdyWindowUpdateFrame.getDeltaWindowSize();

                // Ignore frames for half-closed streams
                if (spdySession.isLocalSideClosed(streamID)) {
                    return;
                }

                // Check for numerical overflow
                if (spdySession.getSendWindowSize(streamID) > Integer.MAX_VALUE - deltaWindowSize) {
                    issueStreamError(ctx, streamID, SpdyStreamStatus.FLOW_CONTROL_ERROR, out);
                    return;
                }

                updateSendWindowSize(streamID, deltaWindowSize, out);
            }
        }

        out.add(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof SpdyProtocolException) {
            issueSessionError(ctx, SpdySessionStatus.PROTOCOL_ERROR);
        }

        super.exceptionCaught(ctx, cause);
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        sendGoAwayFrame(ctx, promise);
    }

    @Override
    public void write(ChannelHandlerContext ctx, MessageList<Object> msgs, ChannelPromise promise) throws Exception {
        MessageList<Object> out = MessageList.newInstance();
        for (int i = 0; i < msgs.size(); i++) {
            Object msg = msgs.get(i);
            if (msg == null) {
                break;
            }
            if (msg instanceof SpdyDataFrame ||
                    msg instanceof SpdySynStreamFrame ||
                    msg instanceof SpdySynReplyFrame ||
                    msg instanceof SpdyRstStreamFrame ||
                    msg instanceof SpdySettingsFrame ||
                    msg instanceof SpdyPingFrame ||
                    msg instanceof SpdyGoAwayFrame ||
                    msg instanceof SpdyHeadersFrame ||
                    msg instanceof SpdyWindowUpdateFrame) {
                try {
                    handleOutboundMessage(ctx, msg, out);
                } catch (SpdyProtocolException e) {
                    if (e == PROTOCOL_EXCEPTION) {
                        // on the case of PROTOCOL_EXCEPTION, fail the promise directly
                        // See #1211
                        promise.setFailure(PROTOCOL_EXCEPTION);
                        return;
                    }
                }
            } else {
                out.add(msg);
            }
        }

        msgs.recycle();
        ctx.write(out, promise);
    }

    private void handleOutboundMessage(ChannelHandlerContext ctx, Object msg, MessageList<Object> out)
            throws Exception {
        if (msg instanceof SpdyDataFrame) {

            SpdyDataFrame spdyDataFrame = (SpdyDataFrame) msg;
            final int streamID = spdyDataFrame.getStreamId();

            // Frames must not be sent on half-closed streams
            if (spdySession.isLocalSideClosed(streamID)) {
                throw PROTOCOL_EXCEPTION;
            }

            /*
             * SPDY Data frame flow control processing requirements:
             *
             * Sender must not send a data frame with data length greater
             * than the transfer window size.
             *
             * After sending each data frame, the sender decrements its
             * transfer window size by the amount of data transmitted.
             *
             * When the window size becomes less than or equal to 0, the
             * sender must pause transmitting data frames.
             */

            if (flowControl) {
                synchronized (flowControlLock) {
                    int dataLength = spdyDataFrame.content().readableBytes();
                    int sendWindowSize = spdySession.getSendWindowSize(streamID);

                    if (sendWindowSize <= 0) {
                        // Stream is stalled -- enqueue Data frame and return
                        spdySession.putPendingWrite(streamID, spdyDataFrame);
                        return;
                    } else if (sendWindowSize < dataLength) {
                        // Stream is not stalled but we cannot send the entire frame
                        spdySession.updateSendWindowSize(streamID, -1 * sendWindowSize);

                        // Create a partial data frame whose length is the current window size
                        SpdyDataFrame partialDataFrame = new DefaultSpdyDataFrame(streamID,
                                spdyDataFrame.content().readSlice(sendWindowSize).retain());

                        // Enqueue the remaining data (will be the first frame queued)
                        spdySession.putPendingWrite(streamID, spdyDataFrame);

                        // The transfer window size is pre-decremented when sending a data frame downstream.
                        // Close the stream on write failures that leaves the transfer window in a corrupt state.
                        //
                        // This is never sent because on write failure the connection will be closed
                        // immediately.  Commenting out just in case I misunderstood it - T
                        //
                        //final SocketAddress remoteAddress = e.getRemoteAddress();
                        //final ChannelHandlerContext context = ctx;
                        //e.getFuture().addListener(new ChannelFutureListener() {
                        //    @Override
                        //    public void operationComplete(ChannelFuture future) throws Exception {
                        //        if (!future.isSuccess()) {
                        //            issueStreamError(context, streamID, SpdyStreamStatus.INTERNAL_ERROR);
                        //        }
                        //    }
                        //});

                        ctx.write(partialDataFrame);
                        return;
                    } else {
                        // Window size is large enough to send entire data frame
                        spdySession.updateSendWindowSize(streamID, -1 * dataLength);

                        // The transfer window size is pre-decremented when sending a data frame downstream.
                        // Close the stream on write failures that leaves the transfer window in a corrupt state.
                        //
                        // This is never sent because on write failure the connection will be closed
                        // immediately.  Commenting out just in case I misunderstood it - T
                        //
                        //final ChannelHandlerContext context = ctx;
                        //e.getFuture().addListener(new ChannelFutureListener() {
                        //    @Override
                        //    public void operationComplete(ChannelFuture future) throws Exception {
                        //        if (!future.isSuccess()) {
                        //            issueStreamError(context, streamID, SpdyStreamStatus.INTERNAL_ERROR);
                        //        }
                        //    }
                        //});
                    }
                }
            }

            // Close the local side of the stream if this is the last frame
            if (spdyDataFrame.isLast()) {
                halfCloseStream(streamID, false);
            }

        } else if (msg instanceof SpdySynStreamFrame) {

            SpdySynStreamFrame spdySynStreamFrame = (SpdySynStreamFrame) msg;
            int streamID = spdySynStreamFrame.getStreamId();

            if (isRemoteInitiatedID(streamID)) {
                throw PROTOCOL_EXCEPTION;
            }

            byte priority = spdySynStreamFrame.getPriority();
            boolean remoteSideClosed = spdySynStreamFrame.isUnidirectional();
            boolean localSideClosed = spdySynStreamFrame.isLast();
            if (!acceptStream(streamID, priority, remoteSideClosed, localSideClosed)) {
                throw PROTOCOL_EXCEPTION;
            }

        } else if (msg instanceof SpdySynReplyFrame) {

            SpdySynReplyFrame spdySynReplyFrame = (SpdySynReplyFrame) msg;
            int streamID = spdySynReplyFrame.getStreamId();

            // Frames must not be sent on half-closed streams
            if (!isRemoteInitiatedID(streamID) || spdySession.isLocalSideClosed(streamID)) {
                throw PROTOCOL_EXCEPTION;
            }

            // Close the local side of the stream if this is the last frame
            if (spdySynReplyFrame.isLast()) {
                halfCloseStream(streamID, false);
            }

        } else if (msg instanceof SpdyRstStreamFrame) {

            SpdyRstStreamFrame spdyRstStreamFrame = (SpdyRstStreamFrame) msg;
            removeStream(ctx, spdyRstStreamFrame.getStreamId());

        } else if (msg instanceof SpdySettingsFrame) {

            SpdySettingsFrame spdySettingsFrame = (SpdySettingsFrame) msg;

            int newConcurrentStreams =
                    spdySettingsFrame.getValue(SpdySettingsFrame.SETTINGS_MAX_CONCURRENT_STREAMS);
            if (newConcurrentStreams >= 0) {
                updateConcurrentStreams(newConcurrentStreams, false);
            }

            // Persistence flag are inconsistent with the use of SETTINGS to communicate
            // the initial window size. Remove flags from the sender requesting that the
            // value be persisted. Remove values that the sender indicates are persisted.
            if (spdySettingsFrame.isPersisted(SpdySettingsFrame.SETTINGS_INITIAL_WINDOW_SIZE)) {
                spdySettingsFrame.removeValue(SpdySettingsFrame.SETTINGS_INITIAL_WINDOW_SIZE);
            }
            spdySettingsFrame.setPersistValue(SpdySettingsFrame.SETTINGS_INITIAL_WINDOW_SIZE, false);

            if (flowControl) {
                int newInitialWindowSize =
                        spdySettingsFrame.getValue(SpdySettingsFrame.SETTINGS_INITIAL_WINDOW_SIZE);
                if (newInitialWindowSize >= 0) {
                    updateInitialReceiveWindowSize(newInitialWindowSize);
                }
            }

        } else if (msg instanceof SpdyPingFrame) {

            SpdyPingFrame spdyPingFrame = (SpdyPingFrame) msg;
            if (isRemoteInitiatedID(spdyPingFrame.getId())) {
                ctx.fireExceptionCaught(new IllegalArgumentException(
                            "invalid PING ID: " + spdyPingFrame.getId()));
                return;
            }
            pings.getAndIncrement();

        } else if (msg instanceof SpdyGoAwayFrame) {

            // Why is this being sent? Intercept it and fail the write.
            // Should have sent a CLOSE ChannelStateEvent
            throw PROTOCOL_EXCEPTION;

        } else if (msg instanceof SpdyHeadersFrame) {

            SpdyHeadersFrame spdyHeadersFrame = (SpdyHeadersFrame) msg;
            int streamID = spdyHeadersFrame.getStreamId();

            // Frames must not be sent on half-closed streams
            if (spdySession.isLocalSideClosed(streamID)) {
                throw PROTOCOL_EXCEPTION;
            }

            // Close the local side of the stream if this is the last frame
            if (spdyHeadersFrame.isLast()) {
                halfCloseStream(streamID, false);
            }

        } else if (msg instanceof SpdyWindowUpdateFrame) {

            // Why is this being sent? Intercept it and fail the write.
            throw PROTOCOL_EXCEPTION;
        }

        out.add(msg);
    }

    /*
     * SPDY Session Error Handling:
     *
     * When a session error occurs, the endpoint encountering the error must first
     * send a GOAWAY frame with the Stream-ID of the most recently received stream
     * from the remote endpoint, and the error code for why the session is terminating.
     *
     * After sending the GOAWAY frame, the endpoint must close the TCP connection.
     */
    private void issueSessionError(
            ChannelHandlerContext ctx, SpdySessionStatus status) {

        sendGoAwayFrame(ctx, status).addListener(ChannelFutureListener.CLOSE);
    }

    /*
     * SPDY Stream Error Handling:
     *
     * Upon a stream error, the endpoint must send a RST_STREAM frame which contains
     * the Stream-ID for the stream where the error occurred and the error getStatus which
     * caused the error.
     *
     * After sending the RST_STREAM, the stream is closed to the sending endpoint.
     *
     * Note: this is only called by the worker thread
     */
    private void issueStreamError(
            ChannelHandlerContext ctx, int streamID, SpdyStreamStatus status, MessageList<Object> in) {

        boolean fireMessageReceived = !spdySession.isRemoteSideClosed(streamID);
        removeStream(ctx, streamID);

        SpdyRstStreamFrame spdyRstStreamFrame = new DefaultSpdyRstStreamFrame(streamID, status);
        ctx.write(spdyRstStreamFrame);
        if (fireMessageReceived) {
            in.add(spdyRstStreamFrame);
            ctx.fireMessageReceived(in.copy());
            in.clear();
        }
    }

    /*
     * Helper functions
     */

    private boolean isRemoteInitiatedID(int id) {
        boolean serverID = SpdyCodecUtil.isServerId(id);
        return server && !serverID || !server && serverID;
    }

    private void updateConcurrentStreams(int newConcurrentStreams, boolean remote) {
        if (remote) {
            remoteConcurrentStreams = newConcurrentStreams;
        } else {
            localConcurrentStreams = newConcurrentStreams;
        }
        if (localConcurrentStreams == remoteConcurrentStreams) {
            maxConcurrentStreams = localConcurrentStreams;
            return;
        }
        if (localConcurrentStreams == 0) {
            maxConcurrentStreams = remoteConcurrentStreams;
            return;
        }
        if (remoteConcurrentStreams == 0) {
            maxConcurrentStreams = localConcurrentStreams;
            return;
        }
        if (localConcurrentStreams > remoteConcurrentStreams) {
            maxConcurrentStreams = remoteConcurrentStreams;
        } else {
            maxConcurrentStreams = localConcurrentStreams;
        }
    }

    // need to synchronize to prevent new streams from being created while updating active streams
    private synchronized void updateInitialSendWindowSize(int newInitialWindowSize) {
        int deltaWindowSize = newInitialWindowSize - initialSendWindowSize;
        initialSendWindowSize = newInitialWindowSize;
        for (Integer streamId: spdySession.getActiveStreams()) {
            spdySession.updateSendWindowSize(streamId.intValue(), deltaWindowSize);
        }
    }

    // need to synchronize to prevent new streams from being created while updating active streams
    private synchronized void updateInitialReceiveWindowSize(int newInitialWindowSize) {
        int deltaWindowSize = newInitialWindowSize - initialReceiveWindowSize;
        initialReceiveWindowSize = newInitialWindowSize;
        spdySession.updateAllReceiveWindowSizes(deltaWindowSize);
    }

    // need to synchronize accesses to sentGoAwayFrame, lastGoodStreamId, and initial window sizes
    private synchronized boolean acceptStream(
            int streamID, byte priority, boolean remoteSideClosed, boolean localSideClosed) {
        // Cannot initiate any new streams after receiving or sending GOAWAY
        if (receivedGoAwayFrame || sentGoAwayFrame) {
            return false;
        }

        int maxConcurrentStreams = this.maxConcurrentStreams; // read volatile once
        if (maxConcurrentStreams != 0 &&
           spdySession.numActiveStreams() >= maxConcurrentStreams) {
            return false;
        }
        spdySession.acceptStream(
                streamID, priority, remoteSideClosed, localSideClosed,
                initialSendWindowSize, initialReceiveWindowSize);
        if (isRemoteInitiatedID(streamID)) {
            lastGoodStreamId = streamID;
        }
        return true;
    }

    private void halfCloseStream(int streamID, boolean remote) {
        if (remote) {
            spdySession.closeRemoteSide(streamID);
        } else {
            spdySession.closeLocalSide(streamID);
        }
        if (closeSessionFuture != null && spdySession.noActiveStreams()) {
            closeSessionFuture.trySuccess();
        }
    }

    private void removeStream(ChannelHandlerContext ctx, int streamID) {
        if (spdySession.removeStream(streamID)) {
            ctx.fireExceptionCaught(STREAM_CLOSED);
        }

        if (closeSessionFuture != null && spdySession.noActiveStreams()) {
            closeSessionFuture.trySuccess();
        }
    }

    private void updateSendWindowSize(final int streamID, int deltaWindowSize, MessageList<Object> out) {
        synchronized (flowControlLock) {
            int newWindowSize = spdySession.updateSendWindowSize(streamID, deltaWindowSize);

            while (newWindowSize > 0) {
                // Check if we have unblocked a stalled stream
                SpdyDataFrame spdyDataFrame = (SpdyDataFrame) spdySession.getPendingWrite(streamID);
                if (spdyDataFrame == null) {
                    break;
                }

                int dataFrameSize = spdyDataFrame.content().readableBytes();

                if (newWindowSize >= dataFrameSize) {
                    // Window size is large enough to send entire data frame
                    spdySession.removePendingWrite(streamID);
                    newWindowSize = spdySession.updateSendWindowSize(streamID, -1 * dataFrameSize);

                    // The transfer window size is pre-decremented when sending a data frame downstream.
                    // Close the stream on write failures that leaves the transfer window in a corrupt state.
                    //
                    // This is never sent because on write failure the connection will be closed
                    // immediately.  Commenting out just in case I misunderstood it - T
                    //
                    //final ChannelHandlerContext context = ctx;
                    //e.getFuture().addListener(new ChannelFutureListener() {
                    //    @Override
                    //    public void operationComplete(ChannelFuture future) throws Exception {
                    //        if (!future.isSuccess()) {
                    //            issueStreamError(context, streamID, SpdyStreamStatus.INTERNAL_ERROR);
                    //        }
                    //    }
                    //});

                    // Close the local side of the stream if this is the last frame
                    if (spdyDataFrame.isLast()) {
                        halfCloseStream(streamID, false);
                    }

                    out.add(spdyDataFrame);
                } else {
                    // We can send a partial frame
                    spdySession.updateSendWindowSize(streamID, -1 * newWindowSize);

                    // Create a partial data frame whose length is the current window size
                    SpdyDataFrame partialDataFrame = new DefaultSpdyDataFrame(streamID,
                            spdyDataFrame.content().readSlice(newWindowSize).retain());

                    // The transfer window size is pre-decremented when sending a data frame downstream.
                    // Close the stream on write failures that leaves the transfer window in a corrupt state.
                    //
                    // This is never sent because on write failure the connection will be closed
                    // immediately.  Commenting out just in case I misunderstood it - T
                    //
                    //final SocketAddress remoteAddress = e.getRemoteAddress();
                    //final ChannelHandlerContext context = ctx;
                    //e.getFuture().addListener(new ChannelFutureListener() {
                    //    @Override
                    //    public void operationComplete(ChannelFuture future) throws Exception {
                    //        if (!future.isSuccess()) {
                    //            issueStreamError(context, streamID, SpdyStreamStatus.INTERNAL_ERROR);
                    //        }
                    //    }
                    //});

                    out.add(partialDataFrame);

                    newWindowSize = 0;
                }
            }
        }
    }

    private void sendGoAwayFrame(ChannelHandlerContext ctx, ChannelPromise future) {
        // Avoid NotYetConnectedException
        if (!ctx.channel().isActive()) {
            ctx.close(future);
            return;
        }

        ChannelFuture f = sendGoAwayFrame(ctx, SpdySessionStatus.OK);
        if (spdySession.noActiveStreams()) {
            f.addListener(new ClosingChannelFutureListener(ctx, future));
        } else {
            closeSessionFuture = ctx.newPromise();
            closeSessionFuture.addListener(new ClosingChannelFutureListener(ctx, future));
        }
        // FIXME: Close the connection forcibly after timeout.
    }

    private synchronized ChannelFuture sendGoAwayFrame(
            ChannelHandlerContext ctx, SpdySessionStatus status) {
        if (!sentGoAwayFrame) {
            sentGoAwayFrame = true;
            SpdyGoAwayFrame spdyGoAwayFrame = new DefaultSpdyGoAwayFrame(lastGoodStreamId, status);
            return ctx.write(spdyGoAwayFrame);
        } else {
            return ctx.newSucceededFuture();
        }
    }

    private static final class ClosingChannelFutureListener implements ChannelFutureListener {
        private final ChannelHandlerContext ctx;
        private final ChannelPromise promise;

        ClosingChannelFutureListener(ChannelHandlerContext ctx, ChannelPromise promise) {
            this.ctx = ctx;
            this.promise = promise;
        }

        @Override
        public void operationComplete(ChannelFuture sentGoAwayFuture) throws Exception {
            ctx.close(promise);
        }
    }
}

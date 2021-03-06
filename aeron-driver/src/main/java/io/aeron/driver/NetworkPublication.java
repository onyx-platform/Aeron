/*
 * Copyright 2014 - 2016 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.driver;

import io.aeron.driver.buffer.RawLog;
import io.aeron.driver.media.SendChannelEndpoint;
import io.aeron.driver.status.SystemCounters;
import io.aeron.logbuffer.LogBufferDescriptor;
import io.aeron.logbuffer.LogBufferPartition;
import io.aeron.logbuffer.LogBufferUnblocker;
import io.aeron.protocol.DataHeaderFlyweight;
import io.aeron.protocol.HeaderFlyweight;
import io.aeron.protocol.SetupFlyweight;
import org.agrona.concurrent.status.AtomicCounter;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.NanoClock;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.Position;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import static io.aeron.driver.status.SystemCounterDescriptor.*;
import static io.aeron.logbuffer.LogBufferDescriptor.*;
import static io.aeron.logbuffer.TermScanner.*;

class NetworkPublicationPadding1
{
    @SuppressWarnings("unused")
    long p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15;
}

class NetworkPublicationConductorFields extends NetworkPublicationPadding1
{
    long timeOfFlush = 0;
    int refCount = 0;
    boolean isActive = true;
}

class NetworkPublicationPadding2 extends NetworkPublicationConductorFields
{
    @SuppressWarnings("unused")
    long p16, p17, p18, p19, p20, p21, p22, p23, p24, p25, p26, p27, p28, p29, p30;
}

class NetworkPublicationReceiverFields extends NetworkPublicationPadding2
{
    long timeOfLastSendOrHeartbeat;
    long senderPositionLimit = 0;
    long timeOfLastSetup;
    boolean trackSenderLimits = true;
    boolean shouldSendSetupFrame = true;
}

class NetworkPublicationPadding3 extends NetworkPublicationReceiverFields
{
    @SuppressWarnings("unused")
    long p31, p32, p33, p34, p35, p36, p37, p38, p39, p40, p41, p42, p43, p44, p45;
}

/**
 * Publication to be sent to registered subscribers.
 */
public class NetworkPublication
    extends NetworkPublicationPadding3
    implements RetransmitSender, DriverManagedResource
{
    private final int positionBitsToShift;
    private final int initialTermId;
    private final int termLengthMask;
    private final int mtuLength;
    private final int termWindowLength;

    private volatile boolean hasStatusMessageBeenReceived = false;
    private boolean reachedEndOfLife = false;

    private final LogBufferPartition[] logPartitions;
    private final ByteBuffer[] sendBuffers;
    private final Position publisherLimit;
    private final Position senderPosition;
    private final SendChannelEndpoint channelEndpoint;
    private final ByteBuffer heartbeatFrameBuffer = ByteBuffer.allocateDirect(DataHeaderFlyweight.HEADER_LENGTH);
    private final DataHeaderFlyweight dataHeader = new DataHeaderFlyweight(heartbeatFrameBuffer);
    private final ByteBuffer setupFrameBuffer = ByteBuffer.allocateDirect(SetupFlyweight.HEADER_LENGTH);
    private final SetupFlyweight setupHeader = new SetupFlyweight(setupFrameBuffer);
    private final FlowControl flowControl;
    private final EpochClock epochClock;
    private final RetransmitHandler retransmitHandler;
    private final RawLog rawLog;
    private final AtomicCounter heartbeatsSent;
    private final AtomicCounter retransmitsSent;
    private final AtomicCounter senderFlowControlLimits;
    private final AtomicCounter dataPacketShortSends;
    private final AtomicCounter setupMessageShortSends;

    public NetworkPublication(
        final SendChannelEndpoint channelEndpoint,
        final NanoClock nanoClock,
        final EpochClock epochClock,
        final RawLog rawLog,
        final Position publisherLimit,
        final Position senderPosition,
        final int sessionId,
        final int streamId,
        final int initialTermId,
        final int mtuLength,
        final SystemCounters systemCounters,
        final FlowControl flowControl,
        final RetransmitHandler retransmitHandler)
    {
        this.channelEndpoint = channelEndpoint;
        this.rawLog = rawLog;
        this.epochClock = epochClock;
        this.senderPosition = senderPosition;
        this.flowControl = flowControl;
        this.retransmitHandler = retransmitHandler;
        this.publisherLimit = publisherLimit;
        this.mtuLength = mtuLength;
        this.initialTermId = initialTermId;

        heartbeatsSent = systemCounters.get(HEARTBEATS_SENT);
        dataPacketShortSends = systemCounters.get(DATA_PACKET_SHORT_SENDS);
        retransmitsSent = systemCounters.get(RETRANSMITS_SENT);
        senderFlowControlLimits = systemCounters.get(SENDER_FLOW_CONTROL_LIMITS);
        setupMessageShortSends = systemCounters.get(SETUP_MESSAGE_SHORT_SENDS);

        logPartitions = rawLog.partitions();
        sendBuffers = rawLog.sliceTerms();

        final int termLength = rawLog.termLength();
        termLengthMask = termLength - 1;
        flowControl.initialize(initialTermId, termLength);

        timeOfLastSendOrHeartbeat = nanoClock.nanoTime() - Configuration.PUBLICATION_HEARTBEAT_TIMEOUT_NS - 1;
        timeOfLastSetup = nanoClock.nanoTime() - Configuration.PUBLICATION_SETUP_TIMEOUT_NS - 1;

        positionBitsToShift = Integer.numberOfTrailingZeros(termLength);
        termWindowLength = Configuration.publicationTermWindowLength(termLength);

        initSetupFrame(initialTermId, termLength, sessionId, streamId);
        initHeartBeatFrame(sessionId, streamId);
    }

    public void close()
    {
        rawLog.close();
        publisherLimit.close();
        senderPosition.close();
    }

    public int send(final long now)
    {
        final long senderPosition = this.senderPosition.get();
        final int activeTermId = computeTermIdFromPosition(senderPosition, positionBitsToShift, initialTermId);
        final int termOffset = (int)senderPosition & termLengthMask;

        if (shouldSendSetupFrame)
        {
            setupMessageCheck(now, activeTermId, termOffset);
        }

        final int bytesSent = sendData(now, senderPosition, termOffset);

        if (0 == bytesSent)
        {
            heartbeatMessageCheck(now, activeTermId, termOffset);
            senderPositionLimit = flowControl.onIdle(now);
        }

        retransmitHandler.processTimeouts(now, this);

        return bytesSent;
    }

    public SendChannelEndpoint sendChannelEndpoint()
    {
        return channelEndpoint;
    }

    public int sessionId()
    {
        return dataHeader.sessionId();
    }

    public int streamId()
    {
        return dataHeader.streamId();
    }

    void senderPositionLimit(final long positionLimit)
    {
        senderPositionLimit = positionLimit;

        if (!hasStatusMessageBeenReceived)
        {
            hasStatusMessageBeenReceived = true;
        }
    }

    /**
     * This is performed on the {@link DriverConductor} thread
     *
     * @return amount of work done
     */
    int cleanLogBuffer()
    {
        int workCount = 0;

        for (final LogBufferPartition partition : logPartitions)
        {
            if (partition.status() == NEEDS_CLEANING)
            {
                partition.clean();
                workCount = 1;
            }
        }

        return workCount;
    }

    public void resend(final int termId, int termOffset, final int length)
    {
        final long senderPosition = this.senderPosition.get();
        final int activeTermId = computeTermIdFromPosition(senderPosition, positionBitsToShift, initialTermId);

        if (termId == activeTermId || termId == (activeTermId - 1))
        {
            final int activeIndex = indexByTerm(initialTermId, termId);
            final UnsafeBuffer termBuffer = logPartitions[activeIndex].termBuffer();
            final ByteBuffer sendBuffer = sendBuffers[activeIndex];

            int remainingBytes = length;
            int bytesSent = 0;
            do
            {
                termOffset += bytesSent;

                final long scanOutcome = scanForAvailability(termBuffer, termOffset, mtuLength);
                final int available = available(scanOutcome);
                if (available <= 0)
                {
                    break;
                }

                sendBuffer.limit(termOffset + available).position(termOffset);

                if (available != channelEndpoint.send(sendBuffer))
                {
                    dataPacketShortSends.orderedIncrement();
                    break;
                }

                bytesSent = available + padding(scanOutcome);
                remainingBytes -= bytesSent;
            }
            while (remainingBytes > 0);

            retransmitsSent.orderedIncrement();
        }
    }

    public void triggerSendSetupFrame()
    {
        shouldSendSetupFrame = true;
    }

    RawLog rawLog()
    {
        return rawLog;
    }

    int publisherLimitId()
    {
        return publisherLimit.id();
    }

    /**
     * Update the publishers limit for flow control as part of the conductor duty cycle.
     *
     * @return 1 if the limit has been updated otherwise 0.
     */
    int updatePublishersLimit()
    {
        int workCount = 0;

        final long candidatePublisherLimit =
            hasStatusMessageBeenReceived ? senderPosition.getVolatile() + termWindowLength : 0L;

        if (publisherLimit.proposeMaxOrdered(candidatePublisherLimit))
        {
            workCount = 1;
        }

        return workCount;
    }

    public void onNak(final int termId, final int termOffset, final int length)
    {
        retransmitHandler.onNak(termId, termOffset, length, this);
    }

    public void onStatusMessage(
        final int termId, final int termOffset, final int receiverWindowLength, final InetSocketAddress srcAddress)
    {
        final long position = flowControl.onStatusMessage(termId, termOffset, receiverWindowLength, srcAddress);
        senderPositionLimit(position);

        final long now = epochClock.time();
        LogBufferDescriptor.timeOfLastStatusMessage(rawLog.logMetaData(), now);
    }

    private int sendData(final long now, final long senderPosition, final int termOffset)
    {
        int bytesSent = 0;
        final int availableWindow = (int)(senderPositionLimit - senderPosition);
        if (availableWindow > 0)
        {
            final int scanLimit = Math.min(availableWindow, mtuLength);
            final int activeIndex = indexByPosition(senderPosition, positionBitsToShift);

            final long scanOutcome = scanForAvailability(logPartitions[activeIndex].termBuffer(), termOffset, scanLimit);
            final int available = available(scanOutcome);
            if (available > 0)
            {
                final ByteBuffer sendBuffer = sendBuffers[activeIndex];
                sendBuffer.limit(termOffset + available).position(termOffset);

                if (available == channelEndpoint.send(sendBuffer))
                {
                    timeOfLastSendOrHeartbeat = now;
                    trackSenderLimits = true;

                    bytesSent = available;
                    this.senderPosition.setOrdered(senderPosition + bytesSent + padding(scanOutcome));
                }
                else
                {
                    dataPacketShortSends.orderedIncrement();
                }
            }
        }
        else if (trackSenderLimits)
        {
            trackSenderLimits = false;
            senderFlowControlLimits.orderedIncrement();
        }

        return bytesSent;
    }

    private void setupMessageCheck(final long now, final int activeTermId, final int termOffset)
    {
        if (now > (timeOfLastSetup + Configuration.PUBLICATION_SETUP_TIMEOUT_NS))
        {
            setupFrameBuffer.clear();
            setupHeader.activeTermId(activeTermId).termOffset(termOffset);

            final int bytesSent = channelEndpoint.send(setupFrameBuffer);
            if (SetupFlyweight.HEADER_LENGTH != bytesSent)
            {
                setupMessageShortSends.orderedIncrement();
            }

            timeOfLastSetup = now;
            timeOfLastSendOrHeartbeat = now;

            if (hasStatusMessageBeenReceived)
            {
                shouldSendSetupFrame = false;
            }
        }
    }

    private void heartbeatMessageCheck(final long now, final int activeTermId, final int termOffset)
    {
        if (now > (timeOfLastSendOrHeartbeat + Configuration.PUBLICATION_HEARTBEAT_TIMEOUT_NS))
        {
            heartbeatFrameBuffer.clear();
            dataHeader.termId(activeTermId).termOffset(termOffset);

            final int bytesSent = channelEndpoint.send(heartbeatFrameBuffer);
            if (DataHeaderFlyweight.HEADER_LENGTH != bytesSent)
            {
                dataPacketShortSends.orderedIncrement();
            }

            heartbeatsSent.orderedIncrement();
            timeOfLastSendOrHeartbeat = now;
        }
    }

    private void initSetupFrame(final int activeTermId, final int termLength, final int sessionId, final int streamId)
    {
        setupHeader
            .sessionId(sessionId)
            .streamId(streamId)
            .initialTermId(initialTermId)
            .activeTermId(activeTermId)
            .termOffset(0)
            .termLength(termLength)
            .mtuLength(mtuLength)
            .ttl(channelEndpoint.multicastTtl())
            .version(HeaderFlyweight.CURRENT_VERSION)
            .flags((byte)0)
            .headerType(HeaderFlyweight.HDR_TYPE_SETUP)
            .frameLength(SetupFlyweight.HEADER_LENGTH);
    }

    private void initHeartBeatFrame(final int sessionId, final int streamId)
    {
        dataHeader
            .sessionId(sessionId)
            .streamId(streamId)
            .version(HeaderFlyweight.CURRENT_VERSION)
            .flags((byte)DataHeaderFlyweight.BEGIN_AND_END_FLAGS)
            .headerType(HeaderFlyweight.HDR_TYPE_DATA)
            .frameLength(0);
    }

    private boolean isUnreferencedAndFlushed(final long now)
    {
        boolean isFlushed = false;
        if (0 == refCount)
        {
            final long senderPosition = this.senderPosition.getVolatile();
            final int activeIndex = indexByPosition(senderPosition, positionBitsToShift);
            isFlushed = (int)(senderPosition & termLengthMask) >= logPartitions[activeIndex].tailOffsetVolatile();

            if (isActive && isFlushed)
            {
                timeOfFlush = now;
                isActive = false;
            }
        }

        return isFlushed;
    }

    public void onTimeEvent(final long time, final DriverConductor conductor)
    {
        if (isUnreferencedAndFlushed(time) && time > (timeOfFlush + Configuration.PUBLICATION_LINGER_NS))
        {
            reachedEndOfLife = true;
            conductor.cleanupPublication(NetworkPublication.this);
        }
    }

    public boolean hasReachedEndOfLife()
    {
        return reachedEndOfLife;
    }

    public void timeOfLastStateChange(final long time)
    {
    }

    public long timeOfLastStateChange()
    {
        return timeOfFlush;
    }

    public void delete()
    {
        // close is done once sender thread has removed
    }

    public int decRef()
    {
        final int count = --refCount;

        if (0 == count)
        {
            channelEndpoint.removePublication(this);
        }

        return count;
    }

    public int incRef()
    {
        return ++refCount;
    }

    public long producerPosition()
    {
        final UnsafeBuffer logMetaDataBuffer = rawLog.logMetaData();
        final int initialTermId = initialTermId(logMetaDataBuffer);
        final long rawTail = logPartitions[activePartitionIndex(logMetaDataBuffer)].rawTailVolatile();
        final int termOffset = termOffset(rawTail, rawLog.termLength());

        return computePosition(termId(rawTail), termOffset, positionBitsToShift, initialTermId);
    }

    public long consumerPosition()
    {
        return senderPosition.getVolatile();
    }

    public boolean unblockAtConsumerPosition()
    {
        return LogBufferUnblocker.unblock(logPartitions, rawLog.logMetaData(), consumerPosition());
    }
}

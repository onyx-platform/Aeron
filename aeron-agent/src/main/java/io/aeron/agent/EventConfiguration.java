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
package io.aeron.agent;

import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor;

import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Common configuration elements between event loggers and event reader side
 */
public class EventConfiguration
{
    /**
     * Event Buffer length system property name
     */
    public static final String BUFFER_LENGTH_PROPERTY_NAME = "aeron.event.buffer.length";

    /**
     * Event tags system property name. This is either:
     * <ul>
     * <li>A comma separated list of EventCodes to enable</li>
     * <li>"all" which enables all the codes</li>
     * <li>"admin" which enables the codes specified by {@link #ADMIN_ONLY_EVENT_CODES}</li>
     * </ul>
     */
    public static final String ENABLED_LOGGER_EVENT_CODES_PROPERTY_NAME = "aeron.event.log";

    public static final Set<EventCode> ADMIN_ONLY_EVENT_CODES = EnumSet.of(
        EventCode.CMD_IN_ADD_PUBLICATION,
        EventCode.CMD_IN_ADD_SUBSCRIPTION,
        EventCode.CMD_IN_KEEPALIVE_CLIENT,
        EventCode.CMD_IN_REMOVE_PUBLICATION,
        EventCode.CMD_IN_REMOVE_SUBSCRIPTION,
        EventCode.REMOVE_IMAGE_CLEANUP,
        EventCode.REMOVE_PUBLICATION_CLEANUP,
        EventCode.REMOVE_SUBSCRIPTION_CLEANUP,
        EventCode.CMD_OUT_PUBLICATION_READY,
        EventCode.CMD_OUT_AVAILABLE_IMAGE,
        EventCode.CMD_OUT_ON_UNAVAILABLE_IMAGE,
        EventCode.CMD_OUT_ON_OPERATION_SUCCESS,
        EventCode.SEND_CHANNEL_CREATION,
        EventCode.RECEIVE_CHANNEL_CREATION,
        EventCode.SEND_CHANNEL_CLOSE,
        EventCode.RECEIVE_CHANNEL_CLOSE);

    public static final Set<EventCode> ALL_LOGGER_EVENT_CODES = EnumSet.allOf(EventCode.class);

    /**
     * Event Buffer default length (in bytes)
     */
    public static final int BUFFER_LENGTH_DEFAULT = 65536;

    /**
     * Maximum length of an event in bytes
     */
    public static final int MAX_EVENT_LENGTH = 4096;

    /**
     * Limit for event reader loop
     */
    public static final int EVENT_READER_FRAME_LIMIT = 8;

    /**
     * The enabled event codes mask
     */
    public static final long ENABLED_EVENT_CODES;

    /**
     * Ring Buffer to use for logging
     */
    public static final ManyToOneRingBuffer EVENT_RING_BUFFER;

    private static final Pattern COMMA = Pattern.compile(",");

    static
    {
        ENABLED_EVENT_CODES =
            makeTagBitSet(getEnabledEventCodes(System.getProperty(ENABLED_LOGGER_EVENT_CODES_PROPERTY_NAME)));

        final int bufferLength =
            Integer.getInteger(
                EventConfiguration.BUFFER_LENGTH_PROPERTY_NAME,
                EventConfiguration.BUFFER_LENGTH_DEFAULT) + RingBufferDescriptor.TRAILER_LENGTH;

        EVENT_RING_BUFFER = new ManyToOneRingBuffer(new UnsafeBuffer(ByteBuffer.allocateDirect(bufferLength)));
    }

    public static long getEnabledEventCodes()
    {
        return ENABLED_EVENT_CODES;
    }

    static long makeTagBitSet(final Set<EventCode> eventCodes)
    {
        return
            eventCodes
                .stream()
                .mapToLong(EventCode::tagBit)
                .reduce(0L, (acc, x) -> acc | x);
    }

    /**
     * Get the {@link Set} of {@link EventCode}s that are enabled for the logger.
     *
     * @param enabledLoggerEventCodes that can be "all", "prod" or a comma separated list.
     * @return the {@link Set} of {@link EventCode}s that are enabled for the logger.
     */
    static Set<EventCode> getEnabledEventCodes(final String enabledLoggerEventCodes)
    {
        if (enabledLoggerEventCodes == null)
        {
            return EnumSet.noneOf(EventCode.class);
        }

        switch (enabledLoggerEventCodes)
        {
            case "all":
                return ALL_LOGGER_EVENT_CODES;

            case "admin":
                return ADMIN_ONLY_EVENT_CODES;

            default:
                return COMMA
                    .splitAsStream(enabledLoggerEventCodes)
                    .map(EventCode::valueOf)
                    .collect(Collectors.toSet());
        }
    }
}

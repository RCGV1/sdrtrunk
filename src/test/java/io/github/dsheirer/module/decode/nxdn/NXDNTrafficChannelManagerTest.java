/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.nxdn;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.module.decode.nxdn.channel.ChannelFrequency;
import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannel;
import io.github.dsheirer.module.decode.nxdn.layer2.LICH;
import io.github.dsheirer.module.decode.nxdn.layer3.NXDNMessageType;
import io.github.dsheirer.module.decode.nxdn.layer3.call.VoiceCall;
import io.github.dsheirer.module.decode.nxdn.layer3.type.ChannelAccessInformation;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class NXDNTrafficChannelManagerTest
{
    @Test
    void identifiesControlChannelUsingTunedFrequency()
    {
        long nominalControlFrequency = 150_000_000L;
        long learnedOffset = 500L;
        Channel parent = new Channel("NXDN test", Channel.ChannelType.STANDARD);
        NXDNTrafficChannelManager manager = new NXDNTrafficChannelManager(parent);

        manager.setControlChannelFrequencyOffset(new TestChannelDescriptor(nominalControlFrequency),
                nominalControlFrequency + learnedOffset);
        manager.setCurrentControlFrequency(nominalControlFrequency + learnedOffset, parent);

        Assertions.assertEquals(nominalControlFrequency + learnedOffset,
                manager.getTunedFrequency(nominalControlFrequency));
        Assertions.assertTrue(manager.isControlFrequency(nominalControlFrequency));
        Assertions.assertFalse(manager.isControlFrequency(nominalControlFrequency + 6_250L));
    }

    @Test
    void retriesRejectedTrafficChannelStartUsingNominalTrackerFrequency()
    {
        long nominalControlFrequency = 150_000_000L;
        long nominalTrafficFrequency = nominalControlFrequency + 6_250L;
        long learnedOffset = 500L;
        DecodeConfigNXDN configuration = new DecodeConfigNXDN();
        configuration.setTrafficChannelPoolSize(1);
        Channel parent = new Channel("NXDN test", Channel.ChannelType.STANDARD);
        parent.setDecodeConfiguration(configuration);
        NXDNTrafficChannelManager manager = new NXDNTrafficChannelManager(parent);
        StartRequestRecorder recorder = new StartRequestRecorder(manager);
        EventBus eventBus = new EventBus();
        eventBus.register(recorder);
        manager.setInterModuleEventBus(eventBus);
        manager.setControlChannelFrequencyOffset(new TestChannelDescriptor(nominalControlFrequency),
                nominalControlFrequency + learnedOffset);

        NXDNChannel trafficChannel = new TestNXDNChannel(nominalTrafficFrequency);
        VoiceCall voiceCall = new VoiceCall(new CorrectedBinaryMessage(80), System.currentTimeMillis(),
                NXDNMessageType.TRAFFIC_OUT_01_CC_VOICE_CALL, 0, LICH.RTCH_OUTBOUND_SUPER_VOICE_VOICE);

        manager.processVoiceCall(voiceCall, trafficChannel);
        Assertions.assertEquals(1, recorder.requests.size());

        manager.processVoiceCall(voiceCall, trafficChannel);
        Assertions.assertEquals(2, recorder.requests.size());
    }

    private static class StartRequestRecorder
    {
        private final List<ChannelStartProcessingRequest> requests = new ArrayList<>();
        private final NXDNTrafficChannelManager mManager;

        private StartRequestRecorder(NXDNTrafficChannelManager manager)
        {
            mManager = manager;
        }

        @Subscribe
        public void receive(ChannelStartProcessingRequest request)
        {
            requests.add(request);
            mManager.getChannelEventListener().receive(new ChannelEvent(request.getChannel(),
                    ChannelEvent.Event.NOTIFICATION_PROCESSING_START_REJECTED, "tuner unavailable"));
        }
    }

    private static class TestNXDNChannel extends NXDNChannel
    {
        private final long mDownlinkFrequency;

        private TestNXDNChannel(long downlinkFrequency)
        {
            mDownlinkFrequency = downlinkFrequency;
        }

        @Override
        public long getDownlinkFrequency()
        {
            return mDownlinkFrequency;
        }

        @Override
        public long getUplinkFrequency()
        {
            return 0;
        }

        @Override
        public void receive(ChannelAccessInformation channelAccessInformation,
                            Map<Integer, ChannelFrequency> channelFrequencyMap)
        {
            // Test channel has a fixed frequency and does not need network configuration.
        }
    }

    private record TestChannelDescriptor(long downlinkFrequency) implements IChannelDescriptor
    {
        @Override
        public long getDownlinkFrequency()
        {
            return downlinkFrequency;
        }

        @Override
        public long getUplinkFrequency()
        {
            return 0;
        }

        @Override
        public boolean isTDMAChannel()
        {
            return false;
        }

        @Override
        public int getTimeslotCount()
        {
            return 1;
        }

        @Override
        public Protocol getProtocol()
        {
            return Protocol.NXDN;
        }
    }
}

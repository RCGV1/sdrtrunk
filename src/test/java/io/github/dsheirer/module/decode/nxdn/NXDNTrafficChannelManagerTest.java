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

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.protocol.Protocol;
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

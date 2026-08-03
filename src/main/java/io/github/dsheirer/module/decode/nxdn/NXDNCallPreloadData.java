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

import io.github.dsheirer.controller.channel.event.PreloadDataContent;
import io.github.dsheirer.module.decode.nxdn.layer3.type.AudioCodec;

/**
 * Authoritative voice-call state from a control-channel grant for a newly started traffic channel.
 */
public class NXDNCallPreloadData extends PreloadDataContent<NXDNCallPreloadData.CallState>
{
    /**
     * Constructs preload data for a traffic-channel voice call.
     *
     * @param encrypted true when the granted call is encrypted
     * @param audioCodec used by the granted call
     */
    public NXDNCallPreloadData(boolean encrypted, AudioCodec audioCodec)
    {
        super(new CallState(encrypted, audioCodec));
    }

    /**
     * Immutable encryption and codec state for the granted call.
     */
    public record CallState(boolean encrypted, AudioCodec audioCodec)
    {
    }
}

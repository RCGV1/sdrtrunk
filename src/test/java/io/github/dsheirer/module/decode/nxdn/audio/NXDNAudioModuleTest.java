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

package io.github.dsheirer.module.decode.nxdn.audio;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.module.decode.nxdn.NXDNCallPreloadData;
import io.github.dsheirer.module.decode.nxdn.layer3.type.AudioCodec;
import io.github.dsheirer.preference.UserPreferences;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class NXDNAudioModuleTest
{
    @Test
    void preloadEstablishesClearCallStateForLateJoinedTrafficAudio() throws ReflectiveOperationException
    {
        NXDNAudioModule module = new NXDNAudioModule(new UserPreferences(), new AliasList("NXDN test"));

        module.preload(new NXDNCallPreloadData(false, AudioCodec.HALF_RATE));

        Assertions.assertTrue(getBoolean(module, "mEncryptedCallStateEstablished"));
        Assertions.assertFalse(getBoolean(module, "mEncryptedCall"));
        Assertions.assertEquals(AudioCodec.HALF_RATE, getField(module, "mAudioCodec"));
    }

    @Test
    void preloadPreservesEncryptedCallState() throws ReflectiveOperationException
    {
        NXDNAudioModule module = new NXDNAudioModule(new UserPreferences(), new AliasList("NXDN test"));

        module.preload(new NXDNCallPreloadData(true, AudioCodec.HALF_RATE));

        Assertions.assertTrue(getBoolean(module, "mEncryptedCallStateEstablished"));
        Assertions.assertTrue(getBoolean(module, "mEncryptedCall"));
    }

    private boolean getBoolean(NXDNAudioModule module, String fieldName) throws ReflectiveOperationException
    {
        return (boolean)getField(module, fieldName);
    }

    private Object getField(NXDNAudioModule module, String fieldName) throws ReflectiveOperationException
    {
        Field field = NXDNAudioModule.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(module);
    }
}

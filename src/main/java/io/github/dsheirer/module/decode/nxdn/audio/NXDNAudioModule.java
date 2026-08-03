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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.nxdn.audio;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.codec.mbe.AmbeAudioModule;
import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.audio.squelch.SquelchStateEvent;
import io.github.dsheirer.dsp.gain.NonClippingGain;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.nxdn.NXDNCallPreloadData;
import io.github.dsheirer.module.decode.nxdn.layer3.call.Audio;
import io.github.dsheirer.module.decode.nxdn.layer3.call.Disconnect;
import io.github.dsheirer.module.decode.nxdn.layer3.call.TransmissionRelease;
import io.github.dsheirer.module.decode.nxdn.layer3.call.VoiceCall;
import io.github.dsheirer.module.decode.nxdn.layer3.call.VoiceCallAssignmentDuplicateTraffic;
import io.github.dsheirer.module.decode.nxdn.layer3.call.VoiceCallWithOptionalLocation;
import io.github.dsheirer.module.decode.nxdn.layer3.type.AudioCodec;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.util.ThreadPool;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * NXDN AMBE audio module
 */
public class NXDNAudioModule extends AmbeAudioModule
{
    private static final int MAX_CACHED_AUDIO_MESSAGES = 12;
    private static final int AUDIO_SAMPLES_PER_FRAME = 160;
    private static final long SQUELCH_CLOSE_GRACE_MILLISECONDS = 1200;
    private static final long SQUELCH_BRIDGE_SILENCE_INTERVAL_MILLISECONDS = 20;
    private static final float[] SQUELCH_BRIDGE_SILENCE = new float[AUDIO_SAMPLES_PER_FRAME];
    private final SquelchStateListener mSquelchStateListener = new SquelchStateListener();
    private final NonClippingGain mGain = new NonClippingGain(5.0f, 0.95f);
    private final List<Audio> mCachedAudioMessages = new ArrayList<>();
    private ScheduledFuture<?> mPendingSquelchCloseFuture;
    private ScheduledFuture<?> mPendingSquelchSilenceFuture;
    private boolean mAudioSegmentOpen = false;
    private boolean mEncryptedCall = false;
    private boolean mEncryptedCallStateEstablished = false;
    private AudioCodec mAudioCodec;

    /**
     * Constructs an instance
     * @param userPreferences component
     * @param aliasList for the current channel
     */
    public NXDNAudioModule(UserPreferences userPreferences, AliasList aliasList)
    {
        super(userPreferences, aliasList, 0);
    }

    @Override
    public Listener<SquelchStateEvent> getSquelchStateListener()
    {
        return mSquelchStateListener;
    }

    @Override
    public void reset()
    {
        getIdentifierCollection().clear();
    }

    @Override
    public void start()
    {
    }

    /**
     * Preloads authoritative call state from the control-channel grant so late-joined clear calls can decode audio.
     */
    @Subscribe
    public void preload(NXDNCallPreloadData preloadData)
    {
        NXDNCallPreloadData.CallState callState = preloadData.getData();
        establishAudioState(callState.encrypted(), callState.audioCodec());
    }

    /**
     * Processes audio and layer 3 messages to decode audio and to determine the encrypted status of a call event.
     */
    public void receive(IMessage message)
    {
        if(hasAudioCodec())
        {
            if(message instanceof Audio audio)
            {
                cancelPendingSquelchClose();

                if(mEncryptedCallStateEstablished)
                {
                    processAudio(audio);
                }
                else
                {
                    //Cache audio until we can determine the encryption state
                    mCachedAudioMessages.add(audio);

                    if(mCachedAudioMessages.size() > MAX_CACHED_AUDIO_MESSAGES)
                    {
                        //Bound memory while waiting for a voice header to establish clear vs. encrypted state.
                        //Dropping the oldest unknown packet is preferable to forcing a scrambled call into clear audio.
                        mCachedAudioMessages.remove(0);
                    }
                }
            }
            else if(message.isValid())
            {
                if(message instanceof VoiceCall voiceCall)
                {
                    if(shouldUseVoiceCallForAudioState(voiceCall))
                    {
                        establishAudioState(voiceCall);
                    }
                }
                else if(message instanceof VoiceCallWithOptionalLocation voiceCall)
                {
                    if(!mEncryptedCallStateEstablished)
                    {
                        establishAudioState(voiceCall);
                    }
                }
                else if(message instanceof Disconnect || message instanceof TransmissionRelease)
                {
                    closeAudioSegmentAndResetCallState();
                }
            }
        }
    }

    /**
     * Uses in-call voice messages freely, but avoids letting duplicate traffic assignments change audio state mid-call.
     */
    private boolean shouldUseVoiceCallForAudioState(VoiceCall voiceCall)
    {
        return !(voiceCall instanceof VoiceCallAssignmentDuplicateTraffic) || !mEncryptedCallStateEstablished;
    }

    /**
     * Establishes the encryption and codec state used to process cached and subsequent audio frames.
     */
    private void establishAudioState(VoiceCall voiceCall)
    {
        establishAudioState(voiceCall.getEncryptionKeyIdentifier().isEncrypted(), voiceCall.getCallOption().getCodec());
    }

    /**
     * Establishes the encryption and codec state used to process cached and subsequent audio frames.
     */
    private void establishAudioState(VoiceCallWithOptionalLocation voiceCall)
    {
        establishAudioState(voiceCall.getEncryptionKeyIdentifier().isEncrypted(), voiceCall.getCallOption().getCodec());
    }

    /**
     * Establishes encryption and codec state from a decoded voice message or an authoritative control-channel grant.
     */
    private void establishAudioState(boolean encrypted, AudioCodec audioCodec)
    {
        cancelPendingSquelchClose();
        mEncryptedCall = encrypted;
        mEncryptedCallStateEstablished = true;
        mAudioCodec = audioCodec;
        processCachedAudio();
    }

    @Override
    public void stop()
    {
        cancelPendingSquelchClose();
        super.stop();
    }

    /**
     * Cancels a delayed squelch close when audio resumes during a short fade/no-sync blip.
     */
    private synchronized void cancelPendingSquelchClose()
    {
        if(mPendingSquelchCloseFuture != null)
        {
            mPendingSquelchCloseFuture.cancel(false);
            mPendingSquelchCloseFuture = null;
        }

        if(mPendingSquelchSilenceFuture != null)
        {
            mPendingSquelchSilenceFuture.cancel(false);
            mPendingSquelchSilenceFuture = null;
        }
    }

    /**
     * Closes the audio segment and resets per-call audio state.
     */
    private synchronized void closeAudioSegmentAndResetCallState()
    {
        cancelPendingSquelchClose();
        closeAudioSegment();
        mAudioSegmentOpen = false;
        mEncryptedCallStateEstablished = false;
        mEncryptedCall = false;
        mCachedAudioMessages.clear();
    }

    /**
     * Schedules a delayed close so brief squelch transitions do not chop active NXDN audio.
     */
    private synchronized void scheduleSquelchClose()
    {
        if(mPendingSquelchCloseFuture == null || mPendingSquelchCloseFuture.isDone())
        {
            mPendingSquelchCloseFuture = ThreadPool.SCHEDULED.schedule(this::closeAudioSegmentAndResetCallState,
                SQUELCH_CLOSE_GRACE_MILLISECONDS, TimeUnit.MILLISECONDS);
        }

        if(mAudioSegmentOpen && (mPendingSquelchSilenceFuture == null || mPendingSquelchSilenceFuture.isDone()))
        {
            mPendingSquelchSilenceFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(this::addSquelchBridgeSilence,
                SQUELCH_BRIDGE_SILENCE_INTERVAL_MILLISECONDS, SQUELCH_BRIDGE_SILENCE_INTERVAL_MILLISECONDS,
                TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Keeps the current audio segment alive during short RF/no-sync fades so playback doesn't abandon it as stalled.
     */
    private synchronized void addSquelchBridgeSilence()
    {
        if(mAudioSegmentOpen && mPendingSquelchCloseFuture != null && !mPendingSquelchCloseFuture.isDone())
        {
            addAudio(SQUELCH_BRIDGE_SILENCE);
        }
    }

    /**
     * Processes any cached audio frames that were pending an encryption state determination.
     */
    private void processCachedAudio()
    {
        for(Audio audio : mCachedAudioMessages)
        {
            processAudio(audio);
        }

        mCachedAudioMessages.clear();
    }

    /**
     * Processes an audio packet by decoding the IMBE audio frames and rebroadcasting them as PCM audio packets.
     */
    private synchronized void processAudio(Audio audio)
    {
        if(!mEncryptedCall && mAudioCodec != null && mAudioCodec.equals(AudioCodec.HALF_RATE)) //Full rate not yet supported
        {
            for(byte[] frame : audio.getAudioFrames())
            {
                float[] generatedAudio = getAudioCodec().getAudio(frame);
                generatedAudio = mGain.apply(generatedAudio);
                addAudio(generatedAudio);
                mAudioSegmentOpen = true;
            }
        }
        else
        {
            //Encrypted audio processing not supported
        }
    }

    /**
     * Wrapper for squelch state to process end of call actions.  At call end the encrypted call state established
     * flag is reset so that the encrypted audio state for the next call can be properly detected and we send an
     * END audio packet so that downstream processors like the audio recorder can properly close out a call sequence.
     */
    public class SquelchStateListener implements Listener<SquelchStateEvent>
    {
        @Override
        public void receive(SquelchStateEvent event)
        {
            if(event.getSquelchState() == SquelchState.SQUELCH)
            {
                scheduleSquelchClose();
            }
            else
            {
                cancelPendingSquelchClose();
            }
        }
    }
}

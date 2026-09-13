package com.cokelord.skyblocksimplified.sound;

import com.cokelord.skyblocksimplified.SkyblockSimplified;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineEvent;
import java.io.File;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Per user request ("Add sound importing to all sound modules and add a toggle for removing deadspace at
 * the start. All sound modules should also have volume and pitch sliders from 0-2, defaulted at 1"): a
 * single reusable "which sound plays, how loud, what pitch" setting, shared by every sound-picking module
 * in the mod (Arrow Hit Sound, Terminal Sounds, Etherwarp success sound, Boss Guide's title-update sound,
 * etc.) instead of each one hand-rolling its own volume-1f/pitch-1f playSound call.
 *
 * <p>Two independent playback paths:
 * <ul>
 *   <li><b>Built-in</b> — one of the feature's own fixed vanilla sound choices, played through Minecraft's
 *   real sound engine ({@code player.playSound(event, volume, pitch)}), so it gets real 3D positioning,
 *   the game's own master/sound-category volume sliders, and true pitch shifting for free.</li>
 *   <li><b>Custom (imported .wav/.mp3/.ogg/.aiff/.au)</b> — decoded once via {@code javax.sound.sampled}
 *   (mp3/ogg support comes from the bundled {@code mp3spi}/{@code vorbisspi} SPI providers registered via
 *   {@code META-INF/services}, so this decode call needs no format-specific code of its own — see this
 *   class's own file-picker filter in {@code MainScreen.drawSoundOptionRows} for the exact supported
 *   extensions) into a cached PCM buffer,
 *   played directly through the JVM's own audio mixer (bypassing Minecraft's sound engine entirely, since
 *   that only ever knows sounds registered ahead of time via a resourcepack's sounds.json — there's no
 *   vanilla API for "play this arbitrary local file"). Volume is applied via the clip's own gain control.
 *   Pitch is a REAL pitch shift (see {@link #pitchShiftPcm}) — per user report ("The pitch speeds it up,
 *   slow it down accordingly so it only pitches instead of speeding up aswell"), this replaced an earlier
 *   version that lied about the sample rate to the audio line (which changes pitch AND playback speed/
 *   duration together, exactly like vanilla's own pitch parameter does) with a real resample-then-time-
 *   stretch algorithm that changes only the perceived pitch while keeping the clip's real duration fixed.
 * </ul>
 *
 * <p>"Remove Deadspace" only applies to the custom path — a built-in vanilla sound's own asset can't be
 * re-encoded from here. Trims leading near-silent samples (a very common issue with downloaded/recorded
 * sound effects that have a beat or more of silence baked into the start of the file, making the sound feel
 * delayed) before caching the clip.
 */
public final class CustomSoundOption {
	private static final float DEAD_SPACE_THRESHOLD = 0.02f; // ~2% of full amplitude

	// Per user report ("The sound doesnt seem to be fully resetting when exiting the game, i can hear it
	// for a split second when rejoining the game"): every Clip ever started (across every CustomSoundOption
	// instance) is tracked here so ClientLifecycleEvents.CLIENT_STOPPING can force-stop them all — a Clip
	// mid-playback at JVM exit was previously never told to stop, only ever closed by its own natural-
	// completion listener, so one that was still playing when the game closed just kept its native audio
	// line open into the next launch's brief startup window. WeakHashMap-backed set: a Clip that finishes
	// normally removes itself via its own STOP listener below, but this is also safe to leak briefly since
	// closed Clips are cheap and eligible for GC either way.
	private static final Set<Clip> ACTIVE_CLIPS = Collections.newSetFromMap(new WeakHashMap<>());

	/** Stops every currently-playing custom sound across every {@link CustomSoundOption} in the mod — call
	 *  on game shutdown and whenever the mod menu itself closes (a preview shouldn't keep playing once the
	 *  settings screen it was triggered from is gone). */
	public static void stopAll() {
		for (Clip clip : Set.copyOf(ACTIVE_CLIPS)) {
			try { clip.stop(); clip.close(); } catch (Exception ignored) {}
		}
		ACTIVE_CLIPS.clear();
	}

	private final String[] builtinIds;
	private final String[] builtinLabels;

	private int builtinIndex = 0;
	private boolean useCustom = false;
	private String customFilePath;
	private float volume = 1f;
	private float pitch = 1f;
	private boolean removeDeadspace = false;

	// Cached decoded+trimmed audio for the current customFilePath, re-decoded only when the path or the
	// Remove Deadspace toggle actually changes — decoding a WAV file on every single play would be wasteful
	// for a sound that might fire many times a minute (e.g. a terminal click chime). Pitch-shifting still
	// happens fresh on every play() (cheap relative to file decoding, and needs to react live to the pitch
	// slider without a cache-invalidation dance).
	private byte[] cachedPcm;
	private AudioFormat cachedFormat;
	private String cachedForPath;
	private boolean cachedForDeadspace;

	// The one Clip this specific option currently has playing, if any — separate from ACTIVE_CLIPS (which
	// is global/all-options) since the mod menu's Preview/Stop button needs to know "is MY sound playing,"
	// not "is anything playing anywhere."
	private volatile Clip activeClip;

	public CustomSoundOption(String[] builtinIds, String[] builtinLabels) {
		this.builtinIds = builtinIds;
		this.builtinLabels = builtinLabels;
	}

	public int getBuiltinIndex() { return builtinIndex; }
	public void setBuiltinIndex(int value) { builtinIndex = ((value % builtinIds.length) + builtinIds.length) % builtinIds.length; }
	public void cycleBuiltin() { setBuiltinIndex(builtinIndex + 1); }
	public String getBuiltinLabel() { return builtinLabels[builtinIndex]; }

	public boolean isUseCustom() { return useCustom; }
	public void setUseCustom(boolean value) { useCustom = value; }

	public String getCustomFilePath() { return customFilePath; }
	public void setCustomFilePath(String path) { customFilePath = path; }

	public float getVolume() { return volume; }
	public void setVolume(float value) { volume = Math.max(0f, Math.min(2f, value)); }

	public float getPitch() { return pitch; }
	public void setPitch(float value) { pitch = Math.max(0f, Math.min(2f, value)); }

	public boolean isRemoveDeadspace() { return removeDeadspace; }
	public void setRemoveDeadspace(boolean value) { removeDeadspace = value; }

	/** Plays through whichever path is currently active. Safe to call even with no player/world loaded
	 *  (built-in path no-ops without a player the same way every existing playSound call site already did;
	 *  the custom path plays through the JVM mixer directly and works with no player at all, which is
	 *  actually convenient for the mod menu's own "preview this sound" button). */
	public void play() {
		if (useCustom && customFilePath != null) {
			playCustom();
		} else {
			playBuiltin();
		}
	}

	/** True while THIS option's own custom-sound preview is actively playing — lets the mod menu swap its
	 *  Preview button for a Stop button. Only meaningful for the custom path; the built-in path plays
	 *  through Minecraft's own sound engine, which this class has no handle to stop mid-playback. */
	public boolean isPlaying() {
		Clip clip = activeClip;
		return clip != null && clip.isOpen();
	}

	/** Stops this option's own currently-playing custom sound, if any. No-op for the built-in path or if
	 *  nothing is currently playing. */
	public void stop() {
		Clip clip = activeClip;
		if (clip != null) {
			try { clip.stop(); } catch (Exception ignored) {}
		}
	}

	// Real bug found (per user report — "the volume slider doesn't make the sound any louder past 100%"):
	// this passes `volume` straight through unclamped (up to the 2f max setVolume allows), but vanilla's own
	// SoundEngine.calculateVolume silently clamps a SoundInstance's own volume component to 1.0 before
	// multiplying it by the player's sound-category option — so this call site was never the problem, and a
	// volume above 1.0 here was always being thrown away deep inside Minecraft's own sound engine regardless
	// of what's passed in. See SoundEngineVolumeMixin (raises that ceiling to 2.0 to match this class's own
	// declared max) for the actual fix — nothing to change here.
	private void playBuiltin() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		Identifier id = Identifier.tryParse(builtinIds[builtinIndex]);
		if (id == null) return;
		SoundEvent event = SoundEvent.createVariableRangeEvent(id);
		mc.player.playSound(event, volume, pitch);
	}

	private void playCustom() {
		try {
			ensureDecoded();
			if (cachedPcm == null || cachedFormat == null) return;
			byte[] playbackPcm = Math.abs(pitch - 1f) < 0.001f ? cachedPcm : pitchShiftPcm(cachedPcm, cachedFormat, pitch);
			// Real bug found (per user report — "the volume slider doesn't seem to update like the pitch
			// does"): this used to apply volume via the Clip's own MASTER_GAIN line control, which — unlike
			// pitch (baked directly into the PCM samples, so it always works) — is an OPTIONAL control some
			// mixers/line configurations silently don't support, in which case setValue is simply never
			// reached and volume does nothing at all regardless of the slider. Scaled directly into the PCM
			// samples instead, same "bake it into the actual samples" approach pitch already uses, so it's
			// guaranteed to work identically everywhere pitch does.
			if (Math.abs(volume - 1f) > 0.001f) playbackPcm = scaleVolume(playbackPcm, cachedFormat, volume);
			DataLine.Info info = new DataLine.Info(Clip.class, cachedFormat);
			if (!AudioSystem.isLineSupported(info)) return;
			Clip clip = (Clip) AudioSystem.getLine(info);
			clip.open(cachedFormat, playbackPcm, 0, playbackPcm.length);
			activeClip = clip;
			ACTIVE_CLIPS.add(clip);
			// Closes itself once done playing (naturally OR via stop()) so this doesn't leak a mixer line per
			// play — a fresh Clip is opened every call anyway (needed since the pitch-shifted PCM can differ
			// play to play).
			clip.addLineListener(event -> {
				if (event.getType() == LineEvent.Type.STOP) {
					ACTIVE_CLIPS.remove(clip);
					if (activeClip == clip) activeClip = null;
					clip.close();
				}
			});
			clip.start();
		} catch (Exception e) {
			SkyblockSimplified.LOGGER.warn("Failed to play custom sound from {}", customFilePath, e);
		}
	}

	private void ensureDecoded() throws Exception {
		if (cachedPcm != null && customFilePath.equals(cachedForPath) && cachedForDeadspace == removeDeadspace) return;
		cachedPcm = null;
		cachedFormat = null;
		File file = new File(customFilePath);
		if (!file.isFile()) return;
		try (AudioInputStream rawIn = AudioSystem.getAudioInputStream(file)) {
			AudioFormat baseFormat = rawIn.getFormat();
			// Normalize to signed 16-bit PCM — the format almost every real .wav/.aiff/.au file already
			// uses, and the only shape the trimming/pitch-shift/gain math below assumes. Non-PCM inputs get
			// converted; AudioSystem throws if it genuinely can't, which the caller already catches.
			AudioFormat pcmFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, baseFormat.getSampleRate(), 16,
				baseFormat.getChannels(), baseFormat.getChannels() * 2, baseFormat.getSampleRate(), false);
			try (AudioInputStream pcmIn = AudioSystem.getAudioInputStream(pcmFormat, rawIn)) {
				byte[] bytes = pcmIn.readAllBytes();
				cachedPcm = removeDeadspace ? trimLeadingSilence(bytes, pcmFormat) : bytes;
				cachedFormat = pcmFormat;
			}
		}
		cachedForPath = customFilePath;
		cachedForDeadspace = removeDeadspace;
	}

	/** Scans 16-bit-signed-PCM frames from the start and cuts everything before the first frame whose peak
	 *  channel amplitude exceeds {@link #DEAD_SPACE_THRESHOLD} of full scale — a plain "wait for real audio"
	 *  scan, not a spectral/RMS analysis, which is more than enough to strip a silent lead-in without risking
	 *  cutting into an intentionally quiet fade-in. */
	private static byte[] trimLeadingSilence(byte[] pcm, AudioFormat format) {
		int frameSize = format.getFrameSize();
		int channels = format.getChannels();
		int threshold = Math.round(DEAD_SPACE_THRESHOLD * Short.MAX_VALUE);
		int frameCount = pcm.length / frameSize;
		for (int frame = 0; frame < frameCount; frame++) {
			int base = frame * frameSize;
			boolean loudEnough = false;
			for (int ch = 0; ch < channels; ch++) {
				int offset = base + ch * 2;
				short sample = (short) ((pcm[offset] & 0xFF) | (pcm[offset + 1] << 8));
				if (Math.abs((int) sample) >= threshold) { loudEnough = true; break; }
			}
			if (loudEnough) {
				if (frame == 0) return pcm;
				byte[] trimmed = new byte[pcm.length - base];
				System.arraycopy(pcm, base, trimmed, 0, trimmed.length);
				return trimmed;
			}
		}
		return pcm; // Entirely silent — nothing to trim to, play it as-is rather than an empty clip.
	}

	// --- Real pitch shifting (resample, then time-stretch back to the original duration) ---
	//
	// Per user report ("The pitch speeds it up, slow it down accordingly so it only pitches instead of
	// speeding up aswell"): a plain "read the samples at a different rate" resample (what the old
	// sample-rate-lie approach amounted to) changes pitch by changing playback speed — raise the pitch and
	// the clip also finishes faster. A real pitch shift needs the clip's DURATION to stay fixed while only
	// the perceived pitch changes. This does that in two steps, both operating on plain PCM shorts (no
	// external DSP library available in this project — see CustomSoundOption's own class doc comment):
	//   1. resample() — linear-interpolation resample by the pitch ratio. This alone reproduces the OLD
	//      buggy behavior exactly (pitch changes, duration changes) — it's not a bugfix by itself.
	//   2. timeStretch() — an overlap-add (OLA) granular time-stretch that takes that pitch-shifted-but-
	//      wrong-length buffer and stretches/compresses it back to the ORIGINAL frame count, by re-placing
	//      overlapping Hann-windowed grains at new time offsets. This step does NOT touch pitch (it doesn't
	//      resample anything — grains keep their samples as-is, just get moved and cross-faded in time), so
	//      the net result is: pitch shifted by step 1, duration restored by step 2 — a real pitch shift.
	// This is a simplified time-domain OLA pitch shifter (the same family as WSOLA/PSOLA, minus their pitch-
	// synchronous grain alignment) — adequate for short one-shot UI/notification sounds, which is all this
	// class is ever used for; not intended for music-quality time-stretching.
	private static byte[] pitchShiftPcm(byte[] pcmBytes, AudioFormat format, float pitch) {
		int channels = format.getChannels();
		boolean bigEndian = format.isBigEndian();
		short[] samples = bytesToShorts(pcmBytes, bigEndian);
		int originalFrames = samples.length / Math.max(1, channels);
		if (originalFrames <= 0) return pcmBytes;
		short[] resampled = resample(samples, channels, Math.max(0.05, pitch));
		short[] stretched = timeStretch(resampled, channels, originalFrames, Math.round(format.getSampleRate()));
		return shortsToBytes(stretched, bigEndian);
	}

	private static short[] bytesToShorts(byte[] pcm, boolean bigEndian) {
		int n = pcm.length / 2;
		short[] out = new short[n];
		for (int i = 0; i < n; i++) {
			int lo = pcm[i * 2] & 0xFF;
			int hi = pcm[i * 2 + 1] & 0xFF;
			out[i] = (short) (bigEndian ? (lo << 8) | hi : (hi << 8) | lo);
		}
		return out;
	}

	private static byte[] shortsToBytes(short[] samples, boolean bigEndian) {
		byte[] out = new byte[samples.length * 2];
		for (int i = 0; i < samples.length; i++) {
			int s = samples[i];
			byte hi = (byte) (s >> 8);
			byte lo = (byte) s;
			if (bigEndian) { out[i * 2] = hi; out[i * 2 + 1] = lo; }
			else { out[i * 2] = lo; out[i * 2 + 1] = hi; }
		}
		return out;
	}

	/** Linear-interpolation resample: reading every {@code rateFactor}-th source frame shortens the buffer
	 *  by that same factor when {@code rateFactor > 1} (and lengthens it when {@code < 1}) while raising (or
	 *  lowering) the waveform's effective frequency by that factor — the actual pitch-shifting step. */
	private static short[] resample(short[] interleaved, int channels, double rateFactor) {
		int frames = interleaved.length / channels;
		int newFrames = Math.max(1, (int) Math.round(frames / rateFactor));
		short[] out = new short[newFrames * channels];
		for (int i = 0; i < newFrames; i++) {
			double srcPos = i * rateFactor;
			int i0 = Math.min((int) Math.floor(srcPos), frames - 1);
			int i1 = Math.min(i0 + 1, frames - 1);
			double frac = srcPos - Math.floor(srcPos);
			for (int ch = 0; ch < channels; ch++) {
				double s0 = interleaved[i0 * channels + ch];
				double s1 = interleaved[i1 * channels + ch];
				double v = s0 + (s1 - s0) * frac;
				out[i * channels + ch] = clampToShort(v);
			}
		}
		return out;
	}

	/** Overlap-add time-stretch: expands (or compresses) {@code input} to exactly {@code outFrames} frames
	 *  by re-placing overlapping, Hann-windowed grains at new time offsets — see this method's own use in
	 *  {@link #pitchShiftPcm} for why this doesn't itself change pitch. */
	private static short[] timeStretch(short[] input, int channels, int outFrames, int sampleRate) {
		int inFrames = input.length / Math.max(1, channels);
		if (inFrames <= 0 || outFrames <= 0) return new short[Math.max(0, outFrames) * channels];

		int grain = Math.max(64, sampleRate / 50); // ~20ms grains
		int hopOut = Math.max(1, grain / 2); // 50% overlap on the output side
		double stretch = (double) outFrames / inFrames;
		int hopIn = Math.max(1, (int) Math.round(hopOut / stretch));

		double[] window = new double[grain];
		for (int i = 0; i < grain; i++) window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / Math.max(1, grain - 1));

		double[] outBuf = new double[outFrames * channels];
		double[] weight = new double[outFrames];

		int inPos = 0, outPos = 0;
		while (outPos < outFrames && inPos < inFrames) {
			int copyLen = Math.min(grain, Math.min(inFrames - inPos, outFrames - outPos));
			for (int i = 0; i < copyLen; i++) {
				double w = window[i];
				weight[outPos + i] += w;
				for (int ch = 0; ch < channels; ch++) {
					outBuf[(outPos + i) * channels + ch] += input[(inPos + i) * channels + ch] * w;
				}
			}
			inPos += hopIn;
			outPos += hopOut;
		}

		short[] result = new short[outFrames * channels];
		for (int i = 0; i < outFrames; i++) {
			double w = weight[i] > 0.0001 ? weight[i] : 1.0;
			for (int ch = 0; ch < channels; ch++) {
				result[i * channels + ch] = clampToShort(outBuf[i * channels + ch] / w);
			}
		}
		return result;
	}

	private static short clampToShort(double v) {
		return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v)));
	}

	/** Bakes {@code volume} directly into the PCM samples — see {@link #playCustom} for why this replaced the
	 *  old {@code MASTER_GAIN} line-control approach (unsupported on some mixers, so the slider silently did
	 *  nothing). Same shape as every other sample-domain transform in this class: bytes to shorts, scale with
	 *  clamping to avoid wraparound clipping, shorts back to bytes. */
	private static byte[] scaleVolume(byte[] pcmBytes, AudioFormat format, float volume) {
		boolean bigEndian = format.isBigEndian();
		short[] samples = bytesToShorts(pcmBytes, bigEndian);
		short[] scaled = new short[samples.length];
		for (int i = 0; i < samples.length; i++) {
			scaled[i] = clampToShort(samples[i] * (double) volume);
		}
		return shortsToBytes(scaled, bigEndian);
	}

	public JsonElement toJson() {
		JsonObject obj = new JsonObject();
		obj.addProperty("builtinIndex", builtinIndex);
		obj.addProperty("useCustom", useCustom);
		if (customFilePath != null) obj.addProperty("customFilePath", customFilePath);
		obj.addProperty("volume", volume);
		obj.addProperty("pitch", pitch);
		obj.addProperty("removeDeadspace", removeDeadspace);
		return obj;
	}

	public void fromJson(JsonElement el) {
		if (!(el instanceof JsonObject obj)) return;
		if (obj.has("builtinIndex")) setBuiltinIndex(obj.get("builtinIndex").getAsInt());
		if (obj.has("useCustom")) useCustom = obj.get("useCustom").getAsBoolean();
		if (obj.has("customFilePath")) customFilePath = obj.get("customFilePath").getAsString();
		if (obj.has("volume")) setVolume(obj.get("volume").getAsFloat());
		if (obj.has("pitch")) setPitch(obj.get("pitch").getAsFloat());
		if (obj.has("removeDeadspace")) removeDeadspace = obj.get("removeDeadspace").getAsBoolean();
	}
}

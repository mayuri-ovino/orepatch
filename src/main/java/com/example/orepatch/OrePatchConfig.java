package com.example.orepatch;

/**
 * Simple in-memory, runtime-toggleable settings for Ore Patch.
 *
 * This intentionally does not persist to disk - it resets to the defaults
 * below every time the server restarts. If you want the toggle to survive
 * restarts, the {@code enabled} and {@code chunksPerTick} fields here are
 * the two values you'd wire up to a config file.
 */
final class OrePatchConfig {

	/** Default scan rate, in chunks processed per server tick. */
	static final int DEFAULT_CHUNKS_PER_TICK = 1;

	private volatile boolean enabled = true;
	private volatile int chunksPerTick = DEFAULT_CHUNKS_PER_TICK;

	boolean isEnabled() {
		return enabled;
	}

	void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	int getChunksPerTick() {
		return chunksPerTick;
	}

	void setChunksPerTick(int chunksPerTick) {
		this.chunksPerTick = Math.max(1, chunksPerTick);
	}
}

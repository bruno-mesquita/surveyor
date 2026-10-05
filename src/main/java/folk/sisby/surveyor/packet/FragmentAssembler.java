package folk.sisby.surveyor.packet;

import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side reassembly of {@link C2SFragmentPacket}s.
 * Everything a client can make the server hold is bounded: pending bytes are capped per player (across all targets), and stale partial packets expire.
 */
public class FragmentAssembler {
	/**
	 * The most bytes a single player can have pending (or assemble) at once - also the largest packet that can be sent fragmented.
	 */
	public static final int MAX_PENDING_BYTES = 2 * 1024 * 1024;
	public static final long EXPIRY_MILLIS = 60_000;

	private static class Pending {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		long lastTouched;
	}

	private final Map<UUID, Map<Identifier, Pending>> pending = new HashMap<>();

	/**
	 * @return the complete payload if this was the last fragment, otherwise null (also null if the fragment was rejected and the partial packet dropped)
	 */
	public synchronized @Nullable byte[] accept(UUID player, Identifier target, boolean last, byte[] data, long nowMillis) {
		Map<Identifier, Pending> playerPending = pending.computeIfAbsent(player, p -> new HashMap<>());
		expire(playerPending, nowMillis);
		Pending partial = playerPending.computeIfAbsent(target, t -> new Pending());
		if (pendingBytes(playerPending) + data.length > MAX_PENDING_BYTES) {
			pending.remove(player);
			return null;
		}
		partial.bytes.writeBytes(data);
		partial.lastTouched = nowMillis;
		if (!last) return null;
		playerPending.remove(target);
		if (playerPending.isEmpty()) pending.remove(player);
		return partial.bytes.toByteArray();
	}

	public synchronized void clear(UUID player) {
		pending.remove(player);
	}

	public synchronized int pendingBytes(UUID player) {
		Map<Identifier, Pending> playerPending = pending.get(player);
		return playerPending == null ? 0 : pendingBytes(playerPending);
	}

	private static int pendingBytes(Map<Identifier, Pending> playerPending) {
		return playerPending.values().stream().mapToInt(p -> p.bytes.size()).sum();
	}

	private static void expire(Map<Identifier, Pending> playerPending, long nowMillis) {
		for (Iterator<Pending> it = playerPending.values().iterator(); it.hasNext(); ) {
			if (nowMillis - it.next().lastTouched > EXPIRY_MILLIS) it.remove();
		}
	}
}

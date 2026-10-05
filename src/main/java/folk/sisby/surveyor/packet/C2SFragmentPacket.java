package folk.sisby.surveyor.packet;

import folk.sisby.surveyor.Surveyor;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * A slice of another C2S packet's serialized bytes, used when that packet exceeds the 32767 byte client-to-server payload limit.
 * The server reassembles slices until {@code last} is set, then handles the result as if it had arrived on {@code target}.
 */
public record C2SFragmentPacket(Identifier target, boolean last, byte[] data) implements C2SPacket {
	public static final Identifier ID = Surveyor.id("c2s_fragment");
	public static final int MAX_C2S_PAYLOAD_SIZE = 32767;
	public static final int FRAGMENT_DATA_SIZE = 30_000;

	public static C2SFragmentPacket read(PacketByteBuf buf) {
		return new C2SFragmentPacket(buf.readIdentifier(), buf.readBoolean(), buf.readByteArray(MAX_C2S_PAYLOAD_SIZE));
	}

	public static List<C2SFragmentPacket> split(Identifier target, ByteBuf payload) {
		List<C2SFragmentPacket> fragments = new ArrayList<>();
		while (payload.isReadable()) {
			byte[] data = new byte[Math.min(FRAGMENT_DATA_SIZE, payload.readableBytes())];
			payload.readBytes(data);
			fragments.add(new C2SFragmentPacket(target, !payload.isReadable(), data));
		}
		return fragments;
	}

	@Override
	public void writeBuf(PacketByteBuf buf) {
		buf.writeIdentifier(target);
		buf.writeBoolean(last);
		buf.writeByteArray(data);
	}

	@Override
	public Identifier getId() {
		return ID;
	}
}

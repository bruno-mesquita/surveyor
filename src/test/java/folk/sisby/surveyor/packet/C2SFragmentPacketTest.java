package folk.sisby.surveyor.packet;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import folk.sisby.surveyor.util.RegionPos;
import io.netty.buffer.Unpooled;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class C2SFragmentPacketTest {
	private static final int VANILLA_C2S_LIMIT = 32767;
	private static final Identifier TARGET = new Identifier("surveyor", "known_terrain");

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.createGameVersion();
		Bootstrap.initialize();
	}

	/**
	 * Mimics what the server's network decoder does with an incoming custom payload - this is where the real "Payload may not be larger than 32767 bytes" comes from.
	 */
	private static CustomPayloadC2SPacket decodeAsServer(Identifier channel, PacketByteBuf data) {
		PacketByteBuf wire = new PacketByteBuf(Unpooled.buffer());
		new CustomPayloadC2SPacket(channel, new PacketByteBuf(data.copy())).write(wire);
		return new CustomPayloadC2SPacket(wire);
	}

	private static PacketByteBuf toBuf(C2SPacket packet) {
		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
		packet.writeBuf(buf);
		return buf;
	}

	private static C2SKnownTerrainPacket bigTerrainPacket(int regions) {
		RegistryKey<World> overworld = RegistryKey.of(RegistryKeys.WORLD, new Identifier("overworld"));
		Random random = new Random(42);
		Table<RegistryKey<World>, RegionPos, BitSet> chunks = HashBasedTable.create();
		for (int i = 0; i < regions; i++) {
			BitSet bits = new BitSet(RegionPos.CHUNK_AREA);
			for (int bit = 0; bit < RegionPos.CHUNK_AREA; bit++) if (random.nextInt(3) != 0) bits.set(bit);
			chunks.put(overworld, new RegionPos(i % 50, i / 50), bits);
		}
		return new C2SKnownTerrainPacket(chunks);
	}

	private static byte[] reassemble(List<C2SFragmentPacket> fragments) {
		FragmentAssembler assembler = new FragmentAssembler();
		UUID player = UUID.randomUUID();
		byte[] result = null;
		for (C2SFragmentPacket fragment : fragments) {
			result = assembler.accept(player, fragment.target(), fragment.last(), decodeFragment(fragment).data(), 0);
		}
		return result;
	}

	/**
	 * Each fragment goes through serialization and the server's size check, like a real send.
	 */
	private static C2SFragmentPacket decodeFragment(C2SFragmentPacket fragment) {
		return C2SFragmentPacket.read(decodeAsServer(fragment.getId(), toBuf(fragment)).getData());
	}

	@Test
	void splitKeepsEveryFragmentUnderTheLimitAndOnlyTheLastIsFlagged() {
		byte[] payload = new byte[200_000];
		new Random(1).nextBytes(payload);
		List<C2SFragmentPacket> fragments = C2SFragmentPacket.split(TARGET, Unpooled.wrappedBuffer(payload));

		assertTrue(fragments.size() > 1);
		for (int i = 0; i < fragments.size(); i++) {
			assertTrue(toBuf(fragments.get(i)).readableBytes() <= VANILLA_C2S_LIMIT, "fragment " + i + " too large");
			assertEquals(i == fragments.size() - 1, fragments.get(i).last());
			assertEquals(TARGET, fragments.get(i).target());
		}
		assertArrayEquals(payload, reassemble(fragments));
	}

	@Test
	void splitOfEmptyPayloadIsEmpty() {
		assertTrue(C2SFragmentPacket.split(TARGET, Unpooled.buffer()).isEmpty());
	}

	@Test
	void oversizedTerrainPacketIsRejectedByTheServerDecoderWithTheRealError() {
		PacketByteBuf buf = toBuf(bigTerrainPacket(500));
		assertTrue(buf.readableBytes() > VANILLA_C2S_LIMIT, "test packet should exceed the limit, was " + buf.readableBytes());
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> decodeAsServer(C2SKnownTerrainPacket.ID, buf));
		assertEquals("Payload may not be larger than 32767 bytes", e.getMessage());
	}

	@Test
	void oversizedTerrainPacketSurvivesFragmentationAndReassembly() {
		C2SKnownTerrainPacket original = bigTerrainPacket(500);
		PacketByteBuf buf = toBuf(original);
		int size = buf.readableBytes();
		assertTrue(size > VANILLA_C2S_LIMIT);
		assertTrue(size <= FragmentAssembler.MAX_PENDING_BYTES);

		List<C2SFragmentPacket> fragments = C2SFragmentPacket.split(C2SKnownTerrainPacket.ID, buf);
		byte[] payload = reassemble(fragments);
		assertNotNull(payload);
		assertEquals(size, payload.length);

		C2SKnownTerrainPacket received = C2SKnownTerrainPacket.read(new PacketByteBuf(Unpooled.wrappedBuffer(payload)));
		assertEquals(original.chunks(), received.chunks());
	}

	@Test
	void fragmentPacketRoundTrips() {
		C2SFragmentPacket original = new C2SFragmentPacket(TARGET, true, new byte[]{1, 2, 3});
		C2SFragmentPacket read = C2SFragmentPacket.read(toBuf(original));
		assertEquals(TARGET, read.target());
		assertTrue(read.last());
		assertArrayEquals(new byte[]{1, 2, 3}, read.data());
		assertFalse(new C2SFragmentPacket(TARGET, false, new byte[0]).last());
	}
}

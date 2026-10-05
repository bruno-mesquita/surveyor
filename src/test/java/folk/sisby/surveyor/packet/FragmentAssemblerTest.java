package folk.sisby.surveyor.packet;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FragmentAssemblerTest {
	private static final UUID PLAYER = UUID.randomUUID();
	private static final Identifier TERRAIN = new Identifier("surveyor", "known_terrain");
	private static final Identifier STRUCTURES = new Identifier("surveyor", "c2s_known_structures");

	@Test
	void returnsPayloadOnlyOnLastFragment() {
		FragmentAssembler assembler = new FragmentAssembler();
		assertNull(assembler.accept(PLAYER, TERRAIN, false, new byte[]{1, 2}, 0));
		assertNull(assembler.accept(PLAYER, TERRAIN, false, new byte[]{3}, 0));
		assertArrayEquals(new byte[]{1, 2, 3, 4}, assembler.accept(PLAYER, TERRAIN, true, new byte[]{4}, 0));
		assertEquals(0, assembler.pendingBytes(PLAYER));
	}

	@Test
	void targetsAndPlayersAreIndependent() {
		FragmentAssembler assembler = new FragmentAssembler();
		UUID other = UUID.randomUUID();
		assembler.accept(PLAYER, TERRAIN, false, new byte[]{1}, 0);
		assembler.accept(PLAYER, STRUCTURES, false, new byte[]{2}, 0);
		assembler.accept(other, TERRAIN, false, new byte[]{3}, 0);
		assertArrayEquals(new byte[]{1, 9}, assembler.accept(PLAYER, TERRAIN, true, new byte[]{9}, 0));
		assertArrayEquals(new byte[]{2, 9}, assembler.accept(PLAYER, STRUCTURES, true, new byte[]{9}, 0));
		assertArrayEquals(new byte[]{3, 9}, assembler.accept(other, TERRAIN, true, new byte[]{9}, 0));
	}

	@Test
	void capIsTotalPerPlayerAndDropsEverythingPending() {
		FragmentAssembler assembler = new FragmentAssembler();
		int half = FragmentAssembler.MAX_PENDING_BYTES / 2 + 1;
		assertNull(assembler.accept(PLAYER, TERRAIN, false, new byte[half], 0));
		assertNull(assembler.accept(PLAYER, STRUCTURES, false, new byte[half], 0));
		assertEquals(0, assembler.pendingBytes(PLAYER));
		assertArrayEquals(new byte[]{5}, assembler.accept(PLAYER, TERRAIN, true, new byte[]{5}, 0));
	}

	@Test
	void singlePacketOverTheCapIsRejectedEvenOnLastFragment() {
		FragmentAssembler assembler = new FragmentAssembler();
		assertNull(assembler.accept(PLAYER, TERRAIN, true, new byte[FragmentAssembler.MAX_PENDING_BYTES + 1], 0));
		assertEquals(0, assembler.pendingBytes(PLAYER));
	}

	@Test
	void exactlyTheCapIsAccepted() {
		FragmentAssembler assembler = new FragmentAssembler();
		assertEquals(FragmentAssembler.MAX_PENDING_BYTES, assembler.accept(PLAYER, TERRAIN, true, new byte[FragmentAssembler.MAX_PENDING_BYTES], 0).length);
	}

	@Test
	void stalePartialPacketsExpire() {
		FragmentAssembler assembler = new FragmentAssembler();
		assembler.accept(PLAYER, TERRAIN, false, new byte[]{1}, 0);
		long later = FragmentAssembler.EXPIRY_MILLIS + 1;
		assertArrayEquals(new byte[]{2}, assembler.accept(PLAYER, TERRAIN, true, new byte[]{2}, later));
	}

	@Test
	void partialPacketsDontExpireWhileFragmentsKeepArriving() {
		FragmentAssembler assembler = new FragmentAssembler();
		long step = FragmentAssembler.EXPIRY_MILLIS - 1;
		assembler.accept(PLAYER, TERRAIN, false, new byte[]{1}, 0);
		assembler.accept(PLAYER, TERRAIN, false, new byte[]{2}, step);
		assertArrayEquals(new byte[]{1, 2, 3}, assembler.accept(PLAYER, TERRAIN, true, new byte[]{3}, step * 2));
	}

	@Test
	void clearDropsPendingData() {
		FragmentAssembler assembler = new FragmentAssembler();
		assembler.accept(PLAYER, TERRAIN, false, new byte[100], 0);
		assembler.clear(PLAYER);
		assertEquals(0, assembler.pendingBytes(PLAYER));
		assertArrayEquals(new byte[]{7}, assembler.accept(PLAYER, TERRAIN, true, new byte[]{7}, 0));
	}
}

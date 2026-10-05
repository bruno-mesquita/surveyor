package folk.sisby.surveyor;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Table;
import folk.sisby.surveyor.config.NetworkMode;
import folk.sisby.surveyor.landmark.Landmark;
import folk.sisby.surveyor.landmark.WorldLandmarks;
import folk.sisby.surveyor.packet.C2SFragmentPacket;
import folk.sisby.surveyor.packet.C2SKnownLandmarksPacket;
import folk.sisby.surveyor.packet.C2SKnownStructuresPacket;
import folk.sisby.surveyor.packet.C2SKnownTerrainPacket;
import folk.sisby.surveyor.packet.C2SPacket;
import folk.sisby.surveyor.packet.FragmentAssembler;
import folk.sisby.surveyor.packet.S2CStructuresAddedPacket;
import folk.sisby.surveyor.packet.SyncLandmarksAddedPacket;
import folk.sisby.surveyor.packet.SyncLandmarksRemovedPacket;
import folk.sisby.surveyor.packet.SyncLandmarksRequestedPacket;
import folk.sisby.surveyor.structure.WorldStructures;
import folk.sisby.surveyor.terrain.WorldTerrain;
import folk.sisby.surveyor.util.MapUtil;
import folk.sisby.surveyor.util.RegionPos;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.gen.structure.Structure;

import java.util.BitSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class SurveyorNetworking {

	public static Consumer<C2SPacket> C2S_SENDER = p -> {
	};

	private static final FragmentAssembler FRAGMENTS = new FragmentAssembler();
	private static final Map<Identifier, FragmentTarget> FRAGMENT_TARGETS = new ConcurrentHashMap<>();

	private interface FragmentReader {
		void handle(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf);
	}

	private record FragmentTarget(FragmentReader reader, Supplier<NetworkMode> mode) {
	}

	private static <T extends C2SPacket> void registerC2S(Identifier id, Function<PacketByteBuf, T> reader, ServerPacketHandler<T> handler) {
		ServerPlayNetworking.registerGlobalReceiver(id, (sv, p, h, b, se) -> handleServer(sv, p, b, reader, handler));
	}

	private static <T extends C2SPacket> void registerFragmentableC2S(Identifier id, Function<PacketByteBuf, T> reader, ServerPacketHandler<T> handler, Supplier<NetworkMode> mode) {
		registerC2S(id, reader, handler);
		FRAGMENT_TARGETS.put(id, new FragmentTarget((sv, p, b) -> handleServer(sv, p, b, reader, handler), mode));
	}

	public static void init() {
		registerFragmentableC2S(C2SKnownTerrainPacket.ID, C2SKnownTerrainPacket::read, SurveyorNetworking::handleKnownTerrain, () -> Surveyor.CONFIG.networking.terrain);
		registerFragmentableC2S(C2SKnownStructuresPacket.ID, C2SKnownStructuresPacket::read, SurveyorNetworking::handleKnownStructures, () -> Surveyor.CONFIG.networking.structures);
		registerFragmentableC2S(C2SKnownLandmarksPacket.ID, C2SKnownLandmarksPacket::read, SurveyorNetworking::handleKnownLandmarks, () -> Surveyor.CONFIG.networking.landmarks);
		registerC2S(SyncLandmarksAddedPacket.ID, SyncLandmarksAddedPacket::read, SurveyorNetworking::handleLandmarksAdded);
		registerC2S(SyncLandmarksRemovedPacket.ID, SyncLandmarksRemovedPacket::read, SurveyorNetworking::handleLandmarksRemoved);
		registerC2S(SyncLandmarksRequestedPacket.ID, SyncLandmarksRequestedPacket::read, SurveyorNetworking::handleLandmarksRequested);
		ServerPlayNetworking.registerGlobalReceiver(C2SFragmentPacket.ID, (sv, p, h, b, se) -> handleFragment(sv, p, C2SFragmentPacket.read(b)));
		ServerPlayConnectionEvents.DISCONNECT.register((h, sv) -> FRAGMENTS.clear(h.getPlayer().getUuid()));
	}

	private static void handleFragment(MinecraftServer server, ServerPlayerEntity player, C2SFragmentPacket fragment) {
		FragmentTarget target = FRAGMENT_TARGETS.get(fragment.target());
		if (target == null || target.mode().get().atMost(NetworkMode.NONE)) return;
		byte[] payload = FRAGMENTS.accept(player.getUuid(), fragment.target(), fragment.last(), fragment.data(), System.currentTimeMillis());
		if (payload == null) return;
		target.reader().handle(server, player, new PacketByteBuf(Unpooled.wrappedBuffer(payload)));
	}

	private static void handleKnownTerrain(MinecraftServer server, ServerPlayerEntity player, C2SKnownTerrainPacket packet) {
		if (Surveyor.CONFIG.networking.terrain.atMost(NetworkMode.NONE)) return;
		int regions = 0;
		int chunks = 0;
		for (ServerWorld world : server.getWorlds()) {
			WorldTerrain terrain = WorldTerrain.of(world);
			if (terrain == null) continue;
			Map<RegionPos, BitSet> serverBits = terrain.bitSet(Surveyor.explorationForMode(Surveyor.CONFIG.networking.terrain, player));
			Map<RegionPos, BitSet> clientBits = packet.chunks().row(world.getRegistryKey());
			for (RegionPos regionPos : serverBits.keySet()) {
				BitSet set = serverBits.get(regionPos);
				if (clientBits.containsKey(regionPos)) set.andNot(clientBits.get(regionPos));
				if (!set.isEmpty()) {
					regions++;
					chunks += set.cardinality();
					terrain.queueUpdate(regionPos, set, player);
				}
			}
		}
		if (regions > 0) Surveyor.LOGGER.info("[Surveyor] Syncing {} missing chunks over {} regions to player {}.", chunks, regions, player.getGameProfile().getName());
	}

	private static void handleKnownStructures(MinecraftServer server, ServerPlayerEntity player, C2SKnownStructuresPacket packet) {
		if (Surveyor.CONFIG.networking.structures.atMost(NetworkMode.NONE)) return;
		for (ServerWorld world : server.getWorlds()) {
			WorldStructures structures = WorldStructures.of(world);
			if (structures == null) continue;
			Multimap<RegistryKey<Structure>, ChunkPos> starts = structures.keySet(Surveyor.explorationForMode(Surveyor.CONFIG.networking.structures, player));
			packet.starts().get(world.getRegistryKey()).forEach(starts::remove);
			if (starts.isEmpty()) continue;
			SurveyorExploration personalExploration = SurveyorExploration.of(player);
			Multimap<RegistryKey<Structure>, ChunkPos> personalStarts = personalExploration.limit(world.getRegistryKey(), HashMultimap.create(starts));
			if (!personalStarts.isEmpty()) S2CStructuresAddedPacket.of(false, personalStarts, structures).send(player);
			personalStarts.forEach(starts::remove);
			if (!starts.isEmpty()) S2CStructuresAddedPacket.of(true, starts, structures).send(player);
			if (!personalStarts.isEmpty() || !starts.isEmpty()) Surveyor.LOGGER.info("[Surveyor] Syncing {} personal and {} shared structures to player {} for {}", personalStarts.size(), starts.size(), player.getGameProfile().getName(), world.getRegistryKey().getValue());
		}
	}

	private static void handleKnownLandmarks(MinecraftServer server, ServerPlayerEntity player, C2SKnownLandmarksPacket packet) {
		if (Surveyor.CONFIG.networking.landmarks.atMost(NetworkMode.NONE)) return;
		UUID uuid = Surveyor.getUuid(player);
		for (ServerWorld world : server.getWorlds()) {
			WorldSummary summary = WorldSummary.of(world);
			WorldLandmarks landmarks = WorldLandmarks.of(world);
			if (summary == null || landmarks == null) continue;
			Multimap<UUID, Identifier> keySet = landmarks.keySet(Surveyor.explorationForMode(Surveyor.CONFIG.networking.landmarks, player));
			Multimap<UUID, Identifier> addLandmarks = HashMultimap.create(keySet);
			if (!Surveyor.CONFIG.forceUpdateLandmarks) packet.landmarks().get(summary.dimension()).forEach(addLandmarks::remove);
			if (!addLandmarks.isEmpty()) SyncLandmarksAddedPacket.of(addLandmarks, landmarks).send(player);
			Multimap<UUID, Identifier> removeLandmarks = HashMultimap.create(packet.landmarks().get(summary.dimension()));
			Multimap<UUID, Identifier> removedLandmarks = landmarks.removed();
			removeLandmarks.entries().removeIf(e -> !(removedLandmarks.containsEntry(e.getKey(), e.getValue()) || (!keySet.containsEntry(e.getKey(), e.getValue()) && !e.getKey().equals(WorldLandmarks.GLOBAL) && !e.getKey().equals(uuid))));
			if (!removeLandmarks.isEmpty()) new SyncLandmarksRemovedPacket(summary.dimension(), removeLandmarks).send(player);
			Multimap<UUID, Identifier> unknownWaypoints = HashMultimap.create();
			unknownWaypoints.putAll(uuid, packet.landmarks().get(summary.dimension()).get(uuid));
			landmarks.keySet(null).get(uuid).forEach(id -> unknownWaypoints.remove(uuid, id));
			removedLandmarks.get(uuid).forEach(id -> unknownWaypoints.remove(uuid, id));
			if (!unknownWaypoints.isEmpty()) new SyncLandmarksRequestedPacket(summary.dimension(), unknownWaypoints).send(player);
			if (!addLandmarks.isEmpty() || !removeLandmarks.isEmpty() || !unknownWaypoints.isEmpty()) Surveyor.LOGGER.info("[Surveyor] Syncing {} landmarks and {} removals and {} unknowns from player {}", addLandmarks.size(), removeLandmarks.size(), unknownWaypoints.size(), player.getGameProfile().getName());
		}
	}

	private static void handleLandmarksAdded(MinecraftServer server, ServerPlayerEntity player, SyncLandmarksAddedPacket packet) {
		if (Surveyor.CONFIG.networking.landmarks.atMost(NetworkMode.NONE)) return;
		ServerWorld world = server.getWorld(packet.dimension());
		if (world == null) return;
		WorldLandmarks landmarks = WorldLandmarks.of(world);
		if (landmarks == null) return;
		Multimap<UUID, Identifier> keys = MapUtil.keyMultiMap(landmarks.readUpdatePacket(packet, player));
		if (!keys.isEmpty()) Surveyor.LOGGER.info("[Surveyor] Adding landmark(s) from player {} - {}", player.getGameProfile().getName(), keys.values().stream().map(Identifier::toString).collect(Collectors.joining(", ")));
	}

	private static void handleLandmarksRemoved(MinecraftServer server, ServerPlayerEntity sender, SyncLandmarksRemovedPacket packet) {
		if (Surveyor.CONFIG.networking.landmarks.atMost(NetworkMode.NONE)) return;
		ServerWorld world = server.getWorld(packet.dimension());
		if (world == null) return;
		WorldLandmarks landmarks = WorldLandmarks.of(world);
		if (landmarks == null) return;
		Table<UUID, Identifier, Landmark> changed = landmarks.readUpdatePacket(packet, sender);
		if (!changed.isEmpty()) {
			Multimap<UUID, Identifier> keys = MapUtil.keyMultiMap(changed);
			Surveyor.LOGGER.info("[Surveyor] Removing landmark(s) for player {} - {}", sender.getGameProfile().getName(), keys.values().stream().map(Identifier::toString).collect(Collectors.joining(", ")));
		}
	}

	private static void handleLandmarksRequested(MinecraftServer server, ServerPlayerEntity player, SyncLandmarksRequestedPacket packet) {
		if (Surveyor.CONFIG.networking.landmarks.atMost(NetworkMode.NONE)) return;
		ServerWorld world = server.getWorld(packet.dimension());
		if (world == null) return;
		WorldLandmarks landmarks = WorldLandmarks.of(world);
		if (landmarks == null) return;
		Multimap<UUID, Identifier> allowed = SurveyorExploration.ofShared(player).limit(world.getRegistryKey(), landmarks, HashMultimap.create(packet.landmarks()));
		if (!allowed.isEmpty()) {
			landmarks.createUpdatePacket(allowed).send(player);
			Surveyor.LOGGER.info("[Surveyor] Sending requested landmark(s) to player {} - {}", player.getGameProfile().getName(), allowed.values().stream().map(Identifier::toString).collect(Collectors.joining(", ")));
		}
	}

	private static <T extends C2SPacket> void handleServer(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf, Function<PacketByteBuf, T> reader, ServerPacketHandler<T> handler) {
		T packet = reader.apply(buf);
		handler.handle(server, player, packet);
	}

	public interface ServerPacketHandler<T> {
		void handle(MinecraftServer server, ServerPlayerEntity player, T packet);
	}
}

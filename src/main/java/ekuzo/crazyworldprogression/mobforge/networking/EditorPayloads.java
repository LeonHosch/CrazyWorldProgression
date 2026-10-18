package ekuzo.crazyworldprogression.mobforge.networking;

import ekuzo.crazyworldprogression.mobforge.MobForge;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public final class EditorPayloads {
	private EditorPayloads() {}
	private static final StreamCodec<RegistryFriendlyByteBuf, String> YAML = ByteBufCodecs.stringUtf8(1_000_000).cast();

	public record OpenScriptEditor(String yaml, String templates, String entities, String abilities,
			boolean existing) implements CustomPacketPayload {
		public static final Type<OpenScriptEditor> TYPE = new Type<>(MobForge.id("open_script_editor"));
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenScriptEditor> CODEC = StreamCodec.composite(YAML, OpenScriptEditor::yaml,
				ByteBufCodecs.stringUtf8(100_000).cast(), OpenScriptEditor::templates,
				ByteBufCodecs.stringUtf8(100_000).cast(), OpenScriptEditor::entities,
				ByteBufCodecs.stringUtf8(100_000).cast(), OpenScriptEditor::abilities,
				ByteBufCodecs.BOOL, OpenScriptEditor::existing,
				OpenScriptEditor::new);
		@Override public Type<OpenScriptEditor> type() { return TYPE; }
	}
	public record SaveScript(String yaml, String originId) implements CustomPacketPayload {
		public static final Type<SaveScript> TYPE = new Type<>(MobForge.id("save_script"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SaveScript> CODEC = StreamCodec.composite(
				YAML, SaveScript::yaml, ByteBufCodecs.stringUtf8(64), SaveScript::originId,
				SaveScript::new);
		@Override public Type<SaveScript> type() { return TYPE; }
	}
	public record DeleteScript(String id) implements CustomPacketPayload {
		public static final Type<DeleteScript> TYPE = new Type<>(MobForge.id("delete_script"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DeleteScript> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(64).cast(), DeleteScript::id, DeleteScript::new);
		@Override public Type<DeleteScript> type() { return TYPE; }
	}
	public record SaveResult(String kind, boolean success, String message) implements CustomPacketPayload {
		public static final Type<SaveResult> TYPE = new Type<>(MobForge.id("editor_save_result"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SaveResult> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, SaveResult::kind,
				ByteBufCodecs.BOOL, SaveResult::success,
				ByteBufCodecs.stringUtf8(8192), SaveResult::message,
				SaveResult::new);
		@Override public Type<SaveResult> type() { return TYPE; }
	}
}

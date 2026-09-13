package com.cokelord.skyblocksimplified.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.GlyphStitcher;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.client.gui.font.providers.GlyphProviderDefinition;
import net.minecraft.client.gui.font.providers.TrueTypeGlyphProviderDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Bakes an arbitrary user-supplied TTF into a genuine, atlas-backed Minecraft {@link GlyphSource} — the
 * SAME kind of object every vanilla and mod font ultimately is — without needing the font to exist inside
 * a registered resource pack (the normal way Minecraft fonts are introduced). A real resource pack requires
 * either baking the mod's own jar at build time ({@code registerBuiltinPack}, used for the bundled
 * Quicksand font) or wiring into {@code PackRepository}'s private, fixed-at-construction source list —
 * neither fits "a user picks an arbitrary file at runtime."
 *
 * <p>The actual trick: {@link TrueTypeGlyphProviderDefinition} (the exact class vanilla itself uses to
 * turn any {@code .ttf} + size/oversample into a real FreeType-backed {@code GlyphProvider}) only needs a
 * {@link ResourceManager} capable of returning the TTF's bytes for whatever {@link Identifier} it asks
 * for — nothing about resource packs or reload cycles is otherwise involved. Handing it a minimal, one-off
 * {@code ResourceManager} that always returns the imported TTF's bytes reuses 100% of Mojang's own real,
 * battle-tested TrueType-baking code (FreeType parsing, hinting, oversampling) with none of the risk of
 * touching the game's actual pack repository or triggering a real resource reload.
 *
 * <p>The raw {@code GlyphProvider} that produces is still just per-codepoint outline data, not something
 * directly renderable — {@link FontSet} (constructed here exactly the way {@code FontManager} builds one
 * for every real font) is what turns it into an atlas-backed, lazily-baked {@link GlyphSource}, matching
 * vanilla's own font pipeline exactly.
 */
public final class CustomFontLoader {
	private CustomFontLoader() {}

	private static final AtomicLong idCounter = new AtomicLong();

	public static final class Loaded implements AutoCloseable {
		public final FontSet fontSet;
		public final GlyphSource source;
		public final EffectGlyph effect;

		private Loaded(FontSet fontSet, GlyphSource source, EffectGlyph effect) {
			this.fontSet = fontSet;
			this.source = source;
			this.effect = effect;
		}

		@Override
		public void close() {
			fontSet.close();
		}
	}

	/** Same size/oversample/shift the bundled Quicksand font already uses (see quicksand.json) — a
	 *  reasonable, already-proven-in-this-mod default for an arbitrary imported font too. */
	public static Loaded load(byte[] ttfBytes) throws Exception {
		Identifier fakeLocation = Identifier.fromNamespaceAndPath("skyblocksimplified", "imported_font_src_" + idCounter.incrementAndGet());
		ResourceManager fakeManager = new SingleFontResourceManager(ttfBytes);

		TrueTypeGlyphProviderDefinition definition = new TrueTypeGlyphProviderDefinition(
			fakeLocation, 11.0f, 16.0f, TrueTypeGlyphProviderDefinition.Shift.NONE, "");
		GlyphProviderDefinition.Loader loader = definition.unpack().left()
			.orElseThrow(() -> new IllegalStateException("TrueTypeGlyphProviderDefinition unexpectedly unpacked to a Reference, not a Loader"));
		com.mojang.blaze3d.font.GlyphProvider provider = loader.load(fakeManager);

		Identifier texturePrefix = Identifier.fromNamespaceAndPath("skyblocksimplified", "imported_font_" + idCounter.incrementAndGet());
		GlyphStitcher stitcher = new GlyphStitcher(Minecraft.getInstance().getTextureManager(), texturePrefix);
		FontSet fontSet = new FontSet(stitcher);
		fontSet.reload(List.of(new com.mojang.blaze3d.font.GlyphProvider.Conditional(provider, FontOption.Filter.ALWAYS_PASS)), Set.of());

		return new Loaded(fontSet, fontSet.source(false), fontSet.whiteGlyph());
	}

	/** Minimal {@link ResourceManager} whose only real job is returning the same in-memory TTF bytes for
	 *  any lookup — {@link TrueTypeGlyphProviderDefinition#unpack()}'s loader only ever calls
	 *  {@code getResource(Identifier)} (via the inherited {@code ResourceProvider.open}), so every other
	 *  method here is an unused stub. */
	private static final class SingleFontResourceManager implements ResourceManager {
		private final byte[] bytes;
		private final PackResources dummyPack = new StubPackResources();

		SingleFontResourceManager(byte[] bytes) {
			this.bytes = bytes;
		}

		@Override
		public Optional<Resource> getResource(Identifier id) {
			IoSupplier<java.io.InputStream> supplier = () -> new ByteArrayInputStream(bytes);
			return Optional.of(new Resource(dummyPack, supplier));
		}

		@Override
		public Set<String> getNamespaces() {
			return Set.of("skyblocksimplified");
		}

		@Override
		public List<Resource> getResourceStack(Identifier id) {
			return getResource(id).map(List::of).orElseGet(List::of);
		}

		@Override
		public Map<Identifier, Resource> listResources(String namespace, java.util.function.Predicate<Identifier> filter) {
			return Map.of();
		}

		@Override
		public Map<Identifier, List<Resource>> listResourceStacks(String namespace, java.util.function.Predicate<Identifier> filter) {
			return Map.of();
		}

		@Override
		public Stream<PackResources> listPacks() {
			return Stream.of(dummyPack);
		}
	}

	/** Every method here is dead code for our purposes — {@code Resource}'s constructor just needs SOME
	 *  non-null {@code PackResources} for bookkeeping (source pack id, etc.) that TrueType baking never
	 *  actually reads. */
	private static final class StubPackResources implements PackResources {
		@Override
		public IoSupplier<java.io.InputStream> getRootResource(String... path) {
			return null;
		}

		@Override
		public IoSupplier<java.io.InputStream> getResource(PackType type, Identifier id) {
			return null;
		}

		@Override
		public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
		}

		@Override
		public Set<String> getNamespaces(PackType type) {
			return Set.of();
		}

		@Override
		public <T> T getMetadataSection(MetadataSectionType<T> type) {
			return null;
		}

		@Override
		public PackLocationInfo location() {
			return new PackLocationInfo("skyblocksimplified_imported_font", net.minecraft.network.chat.Component.literal("Imported Font"),
				net.minecraft.server.packs.repository.PackSource.BUILT_IN, Optional.empty());
		}

		@Override
		public void close() {
		}
	}
}

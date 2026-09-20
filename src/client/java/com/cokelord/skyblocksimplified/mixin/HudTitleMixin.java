package com.cokelord.skyblocksimplified.mixin;

import com.cokelord.skyblocksimplified.dungeon.DungeonState;
import com.cokelord.skyblocksimplified.feature.impl.DungeonTimersFeature;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Storm Lightning Sync (per user request — "count down the storm lightning timer... It should also hide
 *  the original title"): Hypixel's own lightning-strike countdown arrives as a real vanilla title packet
 *  showing a bare number, and {@link Hud#setTitle(Component)} is the one choke point every title (server's
 *  and this mod's own alike) funnels through. Only a title that's essentially a single 1-2 digit number,
 *  while the player is actually in F7's Storm phase, is treated as the lightning countdown — anything else
 *  (including every other title this mod itself fires) passes through untouched.
 *
 *  <p>Real bug found (per user report — "Lightning timer doesn't sync, it doesn't seem to display. Theres
 *  also another gui element that I cant figure out, its like a red 3"): that "mystery red 3" IS the real
 *  lightning-countdown title, arriving un-intercepted — the old check required the title's {@code
 *  getString()} to be PURELY digits with nothing else at all, which is stricter than Hypixel's real title
 *  apparently is (it likely carries a decorative glyph or padding alongside the number that survives
 *  {@code getString()}). Relaxed to "at most one short digit run, with only non-digit clutter around it"
 *  instead of an exact whole-string match, so it still can't mistake an unrelated real title (e.g. one
 *  containing a floor number) for the countdown as long as that title has more than incidental non-digit
 *  content, while now actually catching + hiding the real one during Storm's own narrow phase window. */
@Mixin(Hud.class)
public class HudTitleMixin {
	// Real bug found (per user report — "the storm lightning counter starts at 46 for some reason, it should
	// count down from 6"): the real countdown is a single digit (6 down to 1), but the old \d{1,2} group could
	// match a two-digit title as one number (e.g. "46") if anything else briefly set a bare-number title
	// during Storm's P2 window. Narrowed to exactly one digit 1-9, which can never produce a value the real
	// countdown wouldn't actually reach.
	//
	// Real bug found (per a later user report — "Lightning sync doesnt work anymore"): the surrounding
	// "\D{0,3}" clutter allowance was an unconfirmed guess at how much decorative padding Hypixel's real
	// title carries around the digit — capping it at 3 meant any title with MORE non-digit padding around
	// the number (a longer glyph/space run) never matched at all, silently breaking sync with no error.
	// Uncapped (still anchored start-to-end, still rejecting any OTHER digit or letter in the string) so this
	// can no longer be defeated by however much incidental padding the real title actually carries.
	private static final Pattern LIGHTNING_COUNTDOWN_PATTERN = Pattern.compile("^[^0-9A-Za-z]*([1-9])[^0-9A-Za-z]*$");

	@Inject(method = "setTitle", at = @At("HEAD"), cancellable = true)
	private void skyblocksimplified$stormLightningSync(Component title, CallbackInfo ci) {
		DungeonTimersFeature feature = DungeonTimersFeature.getInstance();
		if (feature == null || !feature.isEnabled() || !feature.isStormLightningSyncEnabled()) return;
		if (DungeonState.getF7Phase() != DungeonState.F7Phase.P2) return;
		String text = title.getString().trim();
		if (text.isEmpty()) return;
		Matcher matcher = LIGHTNING_COUNTDOWN_PATTERN.matcher(text);
		if (!matcher.matches()) return;
		try {
			feature.onStormLightningTitle(Integer.parseInt(matcher.group(1)));
		} catch (NumberFormatException ignored) {
			return;
		}
		ci.cancel();
	}
}

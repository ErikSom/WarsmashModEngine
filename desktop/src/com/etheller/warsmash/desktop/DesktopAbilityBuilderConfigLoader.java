package com.etheller.warsmash.desktop;

import com.etheller.warsmash.viewer5.handlers.w3x.simulation.abilitybuilder.parser.AbilityBuilderConfigLoader;
import com.etheller.warsmash.viewer5.handlers.w3x.simulation.abilitybuilder.parser.AbilityBuilderParserUtil;

final class DesktopAbilityBuilderConfigLoader implements AbilityBuilderConfigLoader {
	static final DesktopAbilityBuilderConfigLoader INSTANCE = new DesktopAbilityBuilderConfigLoader();

	private DesktopAbilityBuilderConfigLoader() {
	}

	@Override
	public void loadAbilityBuilderFiles(final AbilityBuilderFileListener listener) {
		AbilityBuilderParserUtil.loadAbilityBuilderFiles(new AbilityBuilderParserUtil.AbilityBuilderFileListener() {
			@Override
			public void callback(
					final com.etheller.warsmash.viewer5.handlers.w3x.simulation.abilitybuilder.parser.AbilityBuilderParser behavior) {
				listener.callback(behavior);
			}
		});
	}
}

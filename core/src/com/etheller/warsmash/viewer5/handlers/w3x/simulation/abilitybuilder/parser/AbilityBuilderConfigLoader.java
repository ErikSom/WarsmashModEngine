package com.etheller.warsmash.viewer5.handlers.w3x.simulation.abilitybuilder.parser;

/**
 * Platform hook for loading ability-builder JSON definitions.
 *
 * <p>The desktop runtime registers a loader that reads the local
 * {@code abilityBehaviors} directory. Web builds intentionally leave this
 * unregistered so TeaVM does not pull in the file/Gson/reflection path.
 */
public interface AbilityBuilderConfigLoader {
	void loadAbilityBuilderFiles(AbilityBuilderFileListener listener);

	AbilityBuilderConfigLoader[] REGISTRY = new AbilityBuilderConfigLoader[1];

	static void register(final AbilityBuilderConfigLoader loader) {
		REGISTRY[0] = loader;
	}

	static AbilityBuilderConfigLoader get() {
		return REGISTRY[0];
	}

	interface AbilityBuilderFileListener {
		void callback(AbilityBuilderParser behavior);
	}
}

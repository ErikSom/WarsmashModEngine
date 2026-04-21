package com.etheller.warsmash.html;

import com.github.xpenatan.gdx.teavm.backends.web.WebApplication;
import com.github.xpenatan.gdx.teavm.backends.web.WebApplicationConfiguration;

public class HtmlLauncher {
	public static void main(final String[] args) {
		final WebApplicationConfiguration config = new WebApplicationConfiguration();
		config.width = 0;
		config.height = 0;
		config.useGL30 = true;
		new WebApplication(new WarsmashHtmlApp(), config);
	}
}

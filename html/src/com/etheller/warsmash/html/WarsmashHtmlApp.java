package com.etheller.warsmash.html;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

public class WarsmashHtmlApp extends ApplicationAdapter {
	private SpriteBatch batch;
	private BitmapFont font;
	private float time;

	@Override
	public void create() {
		this.batch = new SpriteBatch();
		this.font = new BitmapFont();
	}

	@Override
	public void render() {
		this.time += Gdx.graphics.getDeltaTime();
		final float pulse = 0.5f + 0.5f * (float) Math.sin(this.time);
		Gdx.gl.glClearColor(0.05f, 0.05f + 0.15f * pulse, 0.15f, 1f);
		Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
		this.batch.begin();
		this.font.draw(this.batch, "Warsmash web boot", 20, Gdx.graphics.getHeight() - 20);
		this.batch.end();
	}

	@Override
	public void dispose() {
		this.batch.dispose();
		this.font.dispose();
	}
}

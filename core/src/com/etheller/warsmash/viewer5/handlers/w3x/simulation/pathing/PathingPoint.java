package com.etheller.warsmash.viewer5.handlers.w3x.simulation.pathing;

public final class PathingPoint {
	public float x;
	public float y;

	public PathingPoint(final float x, final float y) {
		this.x = x;
		this.y = y;
	}

	public double distance(final PathingPoint other) {
		return distance(other.x, other.y);
	}

	public double distance(final float otherX, final float otherY) {
		final double dx = this.x - otherX;
		final double dy = this.y - otherY;
		return Math.sqrt((dx * dx) + (dy * dy));
	}

	@Override
	public String toString() {
		return "PathingPoint(" + this.x + "," + this.y + ")";
	}
}

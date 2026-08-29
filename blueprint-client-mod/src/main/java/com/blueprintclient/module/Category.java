package com.blueprintclient.module;

/** The groups the click GUI draws as separate panels. */
public enum Category {
	PERFORMANCE("Performance"),
	VISUAL("Visual"),
	HUD("HUD"),
	MOVEMENT("Movement"),
	ECONOMY("Economy"),
	UTILITY("Utility");

	private final String displayName;

	Category(String displayName) {
		this.displayName = displayName;
	}

	public String getDisplayName() {
		return displayName;
	}
}

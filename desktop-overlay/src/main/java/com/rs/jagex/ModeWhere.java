package com.rs.jagex;

import java.awt.*;

public enum ModeWhere {

	LIVE("LIVE", 0),
	BUILD_LIVE("BUILDLIVE", 3),
	RC("RC", 1),
	WIP("WIP", 2),
	INT_BETA("INTBETA", 4);

	public static Frame method7852(Class470 class470_0, int width, int height, int i_3) {
		if (class470_0 == null) return null;
		Frame window = null;
		try {
			class470_0.selectWindowMonitor();
			DisplayMode mode = FullscreenSupport.select(new DisplayMode[]{class470_0.defaultMoniter.getDisplayMode()},
					class470_0.defaultMoniter.getDisplayMode(), width, height, i_3);
			if (mode == null) return null;
			window = new Frame("Burial Grounds Fullscreen", class470_0.defaultMoniter.getDefaultConfiguration());
			window.setResizable(false);
			Frame ownedWindow = window;
			window.addWindowListener(new java.awt.event.WindowAdapter() {
				@Override public void windowClosing(java.awt.event.WindowEvent event) {
					FullscreenSupport.requestClose(ownedWindow);
				}
			});
			class470_0.method7808(window, mode.getWidth(), mode.getHeight(), mode.getBitDepth(), mode.getRefreshRate());
			return window;
		} catch (RuntimeException failure) {
			if (window != null) Class329.method5903(class470_0, window);
			System.err.println("Fullscreen unavailable: " + failure.getClass().getSimpleName());
			return null;
		}
	}
	static byte method7853(int i_0, int i_1) {
		return (byte) (i_0 != ObjectType.WALL_INTERACT.id ? 0 : ((i_1 & 0x1) == 0 ? 1 : 2));
	}

	public final String name;

	public final int id;

	ModeWhere(String string_1, int i_2) {
		name = string_1;
		id = i_2;
	}

}

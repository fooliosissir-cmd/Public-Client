package com.rs.jagex;

import java.awt.*;
import java.util.Stack;

public class Class470 {

	static int method7823(int i_0) {
		byte b_2;
		if (i_0 > 12097) {
			EquipmentDefaults.method11248();
			b_2 = 4;
		} else if (i_0 > 5098) {
			Node_Sub40.method13300();
			b_2 = 3;
		} else if (i_0 > 2012) {
			Class119.method2077();
			b_2 = 2;
		} else {
			MaterialProp14.method15393();
			b_2 = 1;
		}
		if (Class393.preferences.currentToolkit.getValue() != 2) {
			Class393.preferences.setValue(Class393.preferences.toolKit, 2);
			ParticleProducer.switchRenderType(2, false);
		} else
			Class393.preferences.method13505(Class393.preferences.currentToolkit, true);
		Class190.savePreferences();
		return b_2;
	}

	public static Class285 method7824(boolean bool_0) {
		Stack<Class285> stack_2 = Class285.aStack3390;
		synchronized (Class285.aStack3390) {
			Class285 class285_3;
			if (Class285.aStack3390.isEmpty())
				class285_3 = new Class285();
			else
				class285_3 = Class285.aStack3390.pop();
			class285_3.aBool3392 = bool_0;
			return class285_3;
		}
	}

	static boolean method7825() {
		++client.anInt7221;
		client.aBool7459 = true;
		return true;
	}

	DisplayMode displayMode;

	GraphicsDevice defaultMoniter;

	void selectWindowMonitor() {
		if (displayMode != null) return;
		Component owner = com.rs.Loader.INSTANCE == null ? Engine.engineFrame : com.rs.Loader.INSTANCE.clientFrame;
		if (owner != null && owner.getGraphicsConfiguration() != null) {
			GraphicsDevice device = owner.getGraphicsConfiguration().getDevice();
			defaultMoniter = device;
		}
	}

	public Class470() throws Exception {
		GraphicsEnvironment gfxEnvironment = GraphicsEnvironment.getLocalGraphicsEnvironment();
		defaultMoniter = gfxEnvironment.getDefaultScreenDevice();
	}

	int[] method7807() {
		selectWindowMonitor();
		DisplayMode[] arr_2 = {defaultMoniter.getDisplayMode()};
		int[] ints_3 = new int[arr_2.length << 2];
		for (int i_4 = 0; i_4 < arr_2.length; i_4++) {
			ints_3[i_4 << 2] = arr_2[i_4].getWidth();
			ints_3[(i_4 << 2) + 1] = arr_2[i_4].getHeight();
			ints_3[(i_4 << 2) + 2] = arr_2[i_4].getBitDepth();
			ints_3[(i_4 << 2) + 3] = arr_2[i_4].getRefreshRate();
		}
		return ints_3;
	}

	void method7808(Frame window, int width, int height, int i_4, int i_5) {
		displayMode = defaultMoniter.getDisplayMode();
		if (displayMode == null)
			throw new NullPointerException();
		DisplayMode target = FullscreenSupport.select(new DisplayMode[]{displayMode}, displayMode, width, height, i_4);
		if (target == null) throw new IllegalArgumentException("Unsupported fullscreen display mode");
		window.setUndecorated(true);
		window.enableInputMethods(false);
		// AWT exclusive fullscreen conflicts with this renderer's windowed Direct3D device.
		// Keep the desktop mode and fill the selected monitor with an undecorated native window.
		window.setBounds(defaultMoniter.getDefaultConfiguration().getBounds());
		window.setVisible(true);
	}

	void method7820() {
		displayMode = null;
	}
}

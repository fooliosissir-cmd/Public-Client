package com.rs.jagex;

import com.rs.jagex.clans.settings.ChangeClanSetting;

import java.awt.*;

public class Class507 {

	static Class208 aClass208_5860;

	Class507() throws Throwable {
		throw new Error();
	}

	static void method8725(int currentScreenMode, int desiredScreenMode, int width, int height, boolean isFullScreen) {
		if (desiredScreenMode == 3 && (width <= 0 || height <= 0) && GameDetails.aClass470_3336 != null) {
			GameDetails.aClass470_3336.selectWindowMonitor();
			DisplayMode desktop = GameDetails.aClass470_3336.defaultMoniter.getDisplayMode();
			width = desktop.getWidth();
			height = desktop.getHeight();
		}
		if (Class475.supportsFullScreen && Engine.fullScreenFrame != null && (desiredScreenMode != 3 || width != Class363.anInt4203 || height != Engine.anInt3249)) {
			Class329.method5903(GameDetails.aClass470_3336, Engine.fullScreenFrame);
			Engine.fullScreenFrame = null;
		}
		if (desiredScreenMode == 1 && currentScreenMode != 1)
			UiScale.resizeFixedWindow();
		if (Class475.supportsFullScreen && desiredScreenMode == 3 && Engine.fullScreenFrame == null) {
			Engine.fullScreenFrame = ModeWhere.method7852(GameDetails.aClass470_3336, width, height, 0);
			if (Engine.fullScreenFrame != null) {
				Class363.anInt4203 = width;
				Engine.anInt3249 = height;
				Class190.savePreferences();
			}
		}
		if (desiredScreenMode == 3 && (!Class475.supportsFullScreen || Engine.fullScreenFrame == null))
			method8725(currentScreenMode, FullscreenSupport.windowedFallback(Class393.preferences.screenSize.getValue()), -1, -1, true);
		else {
			Container container_6 = Class371.getActiveContainer();
			Insets insets_7;
			if (Engine.fullScreenFrame != null) {
				SunIndexLoader.anInt434 = width;
				Class107.anInt1082 = height;
			} else if (Engine.engineFrame != null) {
				insets_7 = Engine.engineFrame.getInsets();
				int i_10001 = insets_7.left + insets_7.right;
				SunIndexLoader.anInt434 = Engine.engineFrame.getSize().width - i_10001;
				i_10001 = insets_7.bottom + insets_7.top;
				Class107.anInt1082 = Engine.engineFrame.getSize().height - i_10001;
			} else {
				SunIndexLoader.anInt434 = container_6.getSize().width;
				Class107.anInt1082 = container_6.getSize().height;
			}
			if (SunIndexLoader.anInt434 <= 0)
				SunIndexLoader.anInt434 = 1;
			if (Class107.anInt1082 <= 0)
				Class107.anInt1082 = 1;
			if (desiredScreenMode != 1)
				Class46.method935();
			else {
				UiScale.applyLayout(true, SunIndexLoader.anInt434, Class107.anInt1082);
			}
			int i_10000;
			if (ConnectionInfo.SERVER_ENVIRONMENT != ServerEnvironment.LIVE && ChangeClanSetting.BASE_WINDOW_WIDTH < 1024)
				i_10000 = Engine.BASE_WINDOW_HEIGHT;
			if (!isFullScreen) {
				Class351.gameCanvas.setSize(UiScale.physicalWidth(), UiScale.physicalHeight());
				Renderers.CURRENT_RENDERER.method8414(Class351.gameCanvas, UiScale.physicalWidth(), UiScale.physicalHeight());
				if (container_6 == Engine.engineFrame) {
					insets_7 = Engine.engineFrame.getInsets();
					Class351.gameCanvas.setLocation(insets_7.left + Engine.GAME_CANVAS_X, insets_7.top + Engine.GAME_CANVAS_Y);
				} else
					Class351.gameCanvas.setLocation(Engine.GAME_CANVAS_X, Engine.GAME_CANVAS_Y);
			} else {
				try {
					if (Renderers.CURRENT_RENDERER == null)
						ParticleProducer.switchRenderType(Class393.preferences.currentToolkit.getValue(), true);
					else
						Class350_Sub2.method12571(true);
				} catch (FullscreenHardware.Failure failure) {
					if (desiredScreenMode != 3) throw failure;
					Frame failedFrame = Engine.fullScreenFrame;
					Engine.fullScreenFrame = null;
					if (failedFrame != null) Class329.method5903(GameDetails.aClass470_3336, failedFrame);
					System.err.println("Fullscreen hardware unavailable; restoring the previous window.");
					method8725(currentScreenMode, FullscreenSupport.windowedFallback(currentScreenMode), -1, -1, true);
					return;
				}
			}
			client.resizeableScreen = desiredScreenMode >= 2;
			GameTipsLoader.method6795();
			if (client.BASE_WINDOW_ID != -1)
				Class516.method8867(true);
			if (client.GAME_CONNECTION_CONTEXT.getConnection() != null && GameState.loggedIn(client.GAME_STATE))
				Class388.method6692();
			for (int i_8 = 0; i_8 < 107; i_8++)
				client.IF_COMPONENTS_TO_RENDER[i_8] = true;
			Engine.aBool3274 = true;
		}
	}

	public static MenuActionEvent method8727() {
		return Class20.aCacheableNode_Sub7_168;
	}
}

package dev.tim9h.rcp.webapi;

import java.util.Map;

import com.google.inject.Inject;

import dev.tim9h.rcp.spi.Plugin;
import dev.tim9h.rcp.spi.PluginFactory;

public class WebApiViewFactory implements PluginFactory {

	public static final String SETTING_PORT = "api.port";

	public static final String SETTING_APIKEY = "api.apikeyhash";
	
	public static final String SETTING_ALLOWEDIPS = "api.allowedips";
	
	@Inject
	private WebApiView view;

	@Override
	public String getId() {
		return "webapi";
	}

	@Override
	public Plugin create() {
		return view;
	}

	@Override
	public Map<String, String> getSettingsContributions() {
		return Map.of(SETTING_PORT, "8080", SETTING_APIKEY, "", SETTING_ALLOWEDIPS, "");
	}

}
